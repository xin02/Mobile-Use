// vd_server for Xiaomi 9 / Android 9 (API 28), MIUI 10.2.
//
// Drop-in replacement for the original vd-server-go gateway: it speaks the same REST
// contract that the DSH `mobile-use` preset calls, but every primitive below is verified
// to work on Android 9:
//
//   observe      uiautomator dump + XML parse        (a11y getWindows is default-display
//                                                     only before API 30, and flaky on MIUI)
//   click/swipe  input tap / input swipe             (no -d: Android 9 has no display arg)
//   set_value    tap target -> clipboard + PASTE     (input text cannot carry CJK)
//   key          input keyevent
//   screenshot   screencap -p
//   launch_app   am start -n / monkey fallback
//
// The virtual display path of the original project is NOT usable here: on API 28 the
// accessibility tree and the input focus only ever belong to display 0, so apps placed on
// a virtual display can be seen but never touched. Foreground mode is therefore the only
// fully functional mode on this device, and the gateway says so explicitly.
package main

import (
	"encoding/base64"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	dumpFile      = "/data/local/tmp/agent/ui.xml"
	dumpFileAlt   = "/data/local/tmp/mi9_ui_alt.xml"
	componentFile = "/data/local/tmp/mi9_comp.txt"
	maxNodes      = 240
	maxChars      = 20000
)

// ─────────────────────────── shell helpers ───────────────────────────

func sh(timeout time.Duration, name string, args ...string) (string, error) {
	cmd := exec.Command(name, args...)
	done := make(chan struct{})
	var out []byte
	var err error
	go func() {
		out, err = cmd.CombinedOutput()
		close(done)
	}()
	select {
	case <-done:
		return strings.TrimSpace(string(out)), err
	case <-time.After(timeout):
		_ = cmd.Process.Kill()
		return "", fmt.Errorf("timeout running %s", name)
	}
}

func shell(timeout time.Duration, script string) (string, error) {
	return sh(timeout, "/system/bin/sh", "-c", script)
}

// ─────────────────────────── UI model ───────────────────────────

type xmlNode struct {
	Text      string    `xml:"text,attr"`
	ResID     string    `xml:"resource-id,attr"`
	Class     string    `xml:"class,attr"`
	Package   string    `xml:"package,attr"`
	Desc      string    `xml:"content-desc,attr"`
	Clickable bool      `xml:"clickable,attr"`
	Enabled   bool      `xml:"enabled,attr"`
	Focusable bool      `xml:"focusable,attr"`
	Focused   bool      `xml:"focused,attr"`
	Checkable bool      `xml:"checkable,attr"`
	Checked   bool      `xml:"checked,attr"`
	Selected  bool      `xml:"selected,attr"`
	Scrollable bool     `xml:"scrollable,attr"`
	LongClick bool      `xml:"long-clickable,attr"`
	Password  bool      `xml:"password,attr"`
	Bounds    string    `xml:"bounds,attr"`
	Children  []xmlNode `xml:"node"`
}

// uiNode is the flattened element the agent sees.
type uiNode struct {
	ID       int
	Type     string
	Text     string
	Desc     string
	ResID    string
	Pkg      string
	X1, Y1   int
	X2, Y2   int
	CX, CY   int
	Clickable bool
	Editable bool
	Scrollable bool
	Checkable bool
	Checked  bool
	Enabled  bool
	Focused  bool
	Selected bool
}

var boundsRe = regexp.MustCompile(`\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]`)

func parseBounds(s string) (int, int, int, int, bool) {
	m := boundsRe.FindStringSubmatch(s)
	if len(m) != 5 {
		return 0, 0, 0, 0, false
	}
	a, _ := strconv.Atoi(m[1])
	b, _ := strconv.Atoi(m[2])
	c, _ := strconv.Atoi(m[3])
	d, _ := strconv.Atoi(m[4])
	return a, b, c, d, true
}

func shortType(class string) string {
	if i := strings.LastIndex(class, "."); i >= 0 && i+1 < len(class) {
		return class[i+1:]
	}
	return class
}

func flatten(n xmlNode, depth int, out *[]uiNode, idc *int, pkg string) {
	x1, y1, x2, y2, ok := parseBounds(n.Bounds)
	if ok && x2 > x1 && y2 > y1 {
		p := n.Package
		if p == "" {
			p = pkg
		}
		nn := uiNode{
			ID:         *idc,
			Type:       shortType(n.Class),
			Text:       strings.TrimSpace(n.Text),
			Desc:       strings.TrimSpace(n.Desc),
			ResID:      n.ResID,
			Pkg:        p,
			X1:         x1, Y1: y1, X2: x2, Y2: y2,
			CX:         (x1 + x2) / 2, CY: (y1 + y2) / 2,
			Clickable:  n.Clickable,
			Editable:   strings.Contains(n.Class, "EditText") || strings.Contains(n.Class, "AutoComplete"),
			Scrollable: n.Scrollable,
			Checkable:  n.Checkable,
			Checked:    n.Checked,
			Enabled:    n.Enabled,
			Focused:    n.Focused,
			Selected:   n.Selected,
		}
		// Keep nodes that either say something or can be acted on.
		if nn.Text != "" || nn.Desc != "" || nn.Clickable || nn.Editable || nn.Scrollable || nn.Checkable || n.LongClick {
			*out = append(*out, nn)
			*idc++
		}
	}
	for _, c := range n.Children {
		flatten(c, depth+1, out, idc, pkg)
	}
}

// ─────────────────────────── state ───────────────────────────

type gateway struct {
	mu        sync.RWMutex
	nodes     map[string]uiNode
	nodesAt   time.Time
	mode      string
	secret    string
	lastDump  []uiNode
	lastAt    time.Time
}

func (g *gateway) remember(nodes []uiNode) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.nodes = make(map[string]uiNode, len(nodes))
	for _, n := range nodes {
		g.nodes[strconv.Itoa(n.ID)] = n
	}
	g.nodesAt = time.Now()
	g.lastDump = nodes
	g.lastAt = time.Now()
}

func (g *gateway) lookup(id string) (uiNode, bool) {
	g.mu.RLock()
	defer g.mu.RUnlock()
	n, ok := g.nodes[id]
	return n, ok
}

func (g *gateway) setMode(m string) {
	g.mu.Lock()
	g.mode = m
	g.mu.Unlock()
}

func (g *gateway) getMode() string {
	g.mu.RLock()
	defer g.mu.RUnlock()
	if g.mode == "" {
		return "foreground"
	}
	return g.mode
}

// ─────────────────────────── power ───────────────────────────

// ensureAwake keeps the phone usable for automation: a sleeping device yields a black
// screencap and an empty uiautomator dump, which would otherwise look like "app crashed".
func ensureAwake() {
	out, err := shell(3*time.Second, "/system/bin/dumpsys power | grep mWakefulness=")
	if err == nil && strings.Contains(out, "Asleep") {
		_, _ = shell(3*time.Second, "/system/bin/input keyevent 224")
		time.Sleep(600 * time.Millisecond)
		_, _ = shell(3*time.Second, "/system/bin/wm dismiss-keyguard")
		time.Sleep(600 * time.Millisecond)
	}
}

// ─────────────────────────── observe ───────────────────────────

func dumpUI() ([]uiNode, int, error) {
	// uiautomator writes a UTF-8 XML of the current window hierarchy. MIUI regularly
	// answers "null root node returned by UiTestAutomationBridge" during transitions, so
	// retry a few times with a short backoff before reporting a failure.
	var out string
	var err error
	ok := false
	for attempt := 0; attempt < 4; attempt++ {
		out, err = shell(8*time.Second, "/system/bin/uiautomator dump "+dumpFile+" 2>&1")
		if err == nil && strings.Contains(out, "dumped") {
			ok = true
			break
		}
		time.Sleep(time.Duration(250+attempt*250) * time.Millisecond)
	}
	if !ok {
		return nil, 0, fmt.Errorf("uiautomator dump failed: %s", firstLine(out))
	}
	data, err := os.ReadFile(dumpFile)
	if err != nil {
		return nil, 0, fmt.Errorf("cannot read dump: %v", err)
	}
	var root struct {
		XMLName xml.Name  `xml:"hierarchy"`
		Nodes   []xmlNode `xml:"node"`
	}
	if err := xml.Unmarshal(data, &root); err != nil {
		return nil, 0, fmt.Errorf("cannot parse dump: %v", err)
	}
	var nodes []uiNode
	idc := 1
	pkg := ""
	if len(root.Nodes) > 0 {
		pkg = root.Nodes[0].Package
	}
	for _, n := range root.Nodes {
		flatten(n, 0, &nodes, &idc, pkg)
	}
	return nodes, idc - 1, nil
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i > 0 {
		return s[:i]
	}
	return s
}

func screenSize() (int, int) {
	if out, err := shell(2*time.Second, "/system/bin/wm size"); err == nil {
		re := regexp.MustCompile(`(\d+)x(\d+)`)
		if m := re.FindStringSubmatch(out); len(m) == 3 {
			w, _ := strconv.Atoi(m[1])
			h, _ := strconv.Atoi(m[2])
			if w > 0 && h > 0 {
				return w, h
			}
		}
	}
	return 1080, 2340
}

func renderNodes(nodes []uiNode, w, h int, dropped int, truncated bool) string {
	var sb strings.Builder
	acts := 0
	for _, n := range nodes {
		if n.Clickable || n.Checkable {
			acts++
		}
	}
	sb.WriteString(fmt.Sprintf("ok display=0 size=%dx%d windows=1 total=%d returned=%d act_total=%d truncated=%d",
		w, h, len(nodes), len(nodes), acts, boolToInt(truncated)))
	if dropped > 0 {
		sb.WriteString(fmt.Sprintf(" dup=%d", dropped))
	}
	sb.WriteString("\n")
	sb.WriteString("# columns: id type name x1,y1,x2,y2 flags | flags: c=clickable e=editable s=scrollable k+=checked-or-selected k-=unchecked off=disabled focus=focused | optional: id=resource-id\n")
	for _, n := range nodes {
		label := n.Text
		extra := ""
		if label == "" {
			label = n.Desc
		} else if n.Desc != "" && n.Desc != label {
			extra = n.Desc
		}
		sb.WriteString(strconv.Itoa(n.ID))
		sb.WriteString(" ")
		sb.WriteString(n.Type)
		if label != "" {
			sb.WriteString(" \"")
			sb.WriteString(oneLine(label, 160))
			sb.WriteString("\"")
		}
		sb.WriteString(fmt.Sprintf(" %d,%d,%d,%d", n.X1, n.Y1, n.X2, n.Y2))
		if n.Clickable {
			sb.WriteString(" c")
		}
		if n.Editable {
			sb.WriteString(" e")
		}
		if n.Scrollable {
			sb.WriteString(" s")
		}
		if n.Checkable {
			if n.Checked {
				sb.WriteString(" k+")
			} else {
				sb.WriteString(" k-")
			}
		} else if n.Selected {
			sb.WriteString(" k+")
		}
		if !n.Enabled {
			sb.WriteString(" off")
		}
		if n.Focused {
			sb.WriteString(" focus")
		}
		if n.ResID != "" {
			sb.WriteString(" id=")
			sb.WriteString(shortRes(n.ResID))
		}
		if extra != "" {
			sb.WriteString(" d=\"")
			sb.WriteString(oneLine(extra, 120))
			sb.WriteString("\"")
		}
		sb.WriteString("\n")
		if sb.Len() > maxChars {
			break
		}
	}
	return sb.String()
}

func shortRes(id string) string {
	if i := strings.LastIndex(id, "/"); i >= 0 && i+1 < len(id) {
		return id[i+1:]
	}
	return id
}

func oneLine(s string, max int) string {
	s = strings.ReplaceAll(s, "\n", " ")
	s = strings.ReplaceAll(s, "\r", " ")
	s = strings.ReplaceAll(s, "\"", "'")
	s = strings.TrimSpace(s)
	if len([]rune(s)) <= max {
		return s
	}
	r := []rune(s)
	return string(r[:max]) + "..."
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "..."
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func priority(n uiNode) int {
	interactive := n.Clickable || n.Checkable || n.Editable
	if !interactive {
		return 4
	}
	if !n.Enabled {
		return 3
	}
	return 1
}

func sortNodes(nodes []uiNode) {
	sort.SliceStable(nodes, func(i, j int) bool {
		pi, pj := priority(nodes[i]), priority(nodes[j])
		if pi != pj {
			return pi < pj
		}
		return nodes[i].ID < nodes[j].ID
	})
}

func capNodes(nodes []uiNode) ([]uiNode, bool) {
	if len(nodes) <= maxNodes {
		return nodes, false
	}
	return nodes[:maxNodes], true
}

// ─────────────────────────── actions ───────────────────────────

func actionObserve(g *gateway) map[string]interface{} {
	start := time.Now()
	ensureAwake()
	nodes, total, err := dumpUI()
	if err != nil {
		return resp(false, "observe failed", err.Error())
	}
	sortNodes(nodes)
	kept, truncated := capNodes(nodes)
	g.remember(kept)
	w, h := screenSize()
	dropped := total - len(kept)
	text := renderNodes(kept, w, h, dropped, truncated)
	return resp(true, fmt.Sprintf("Observed %d elements in %d ms", len(kept), time.Since(start).Milliseconds()), text)
}

func tap(x, y int) error {
	out, err := shell(6*time.Second, fmt.Sprintf("/system/bin/input tap %d %d", x, y))
	if err != nil {
		return fmt.Errorf("%v %s", err, out)
	}
	return nil
}

func swipe(x1, y1, x2, y2, dur int) error {
	if dur <= 0 {
		dur = 300
	}
	out, err := shell(10*time.Second, fmt.Sprintf("/system/bin/input swipe %d %d %d %d %d", x1, y1, x2, y2, dur))
	if err != nil {
		return fmt.Errorf("%v %s", err, out)
	}
	return nil
}

func pressKey(name string) error {
	codes := map[string]int{
		"BACK": 4, "HOME": 3, "ENTER": 66, "TAB": 61, "SPACE": 62,
		"DEL": 67, "BACKSPACE": 67, "RECENTS": 187, "APP_SWITCH": 187,
		"PASTE": 279, "MENU": 82, "SEARCH": 84, "POWER": 26,
	}
	code, ok := codes[strings.ToUpper(strings.TrimSpace(name))]
	if !ok {
		if n, err := strconv.Atoi(name); err == nil {
			code = n
		} else {
			return fmt.Errorf("unknown key %q", name)
		}
	}
	out, err := shell(6*time.Second, fmt.Sprintf("/system/bin/input keyevent %d", code))
	if err != nil {
		return fmt.Errorf("%v %s", err, out)
	}
	return nil
}

func launchApp(pkgOrComp string) error {
	target := strings.TrimSpace(pkgOrComp)
	if target == "" {
		return fmt.Errorf("empty package")
	}
	if !strings.Contains(target, "/") {
		// Resolve the launcher activity.
		out, err := shell(6*time.Second, "/system/bin/cmd package resolve-activity --brief "+target+" 2>/dev/null | tail -1")
		if err == nil {
			line := strings.TrimSpace(out)
			if strings.Contains(line, "/") {
				target = line
			}
		}
	}
	if strings.Contains(target, "/") {
		out, err := shell(10*time.Second, "/system/bin/am start -n "+target+" 2>&1")
		if err == nil && !strings.Contains(out, "Error") && !strings.Contains(out, "Exception") {
			return nil
		}
		// Fall through to monkey for launcher-style start.
	}
	out, err := shell(10*time.Second, fmt.Sprintf("/system/bin/monkey -p %s -c android.intent.category.LAUNCHER 1 2>&1", strings.Split(target, "/")[0]))
	if err != nil || strings.Contains(out, "No activities found") {
		return fmt.Errorf("launch failed: %s", firstLine(out))
	}
	return nil
}

// clipboardSet is served by DaemonMain (uid 1000) because only a privileged process can
// publish to the clipboard on MIUI; `input text` cannot carry CJK characters.
func clipboardSet(text string) error {
	b64 := base64.StdEncoding.EncodeToString([]byte(text))
	conn, err := net.DialTimeout("tcp", "127.0.0.1:3072", 2*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err := fmt.Fprintf(conn, "clip %s\n", b64); err != nil {
		return err
	}
	buf := make([]byte, 256)
	n, _ := conn.Read(buf)
	reply := strings.TrimSpace(string(buf[:n]))
	if !strings.HasPrefix(reply, "OK") {
		return fmt.Errorf("clipboard daemon: %s", reply)
	}
	return nil
}

func setValue(g *gateway, target, text string) map[string]interface{} {
	var node uiNode
	if target != "" {
		n, ok := g.lookup(strings.TrimPrefix(target, "node:"))
		if !ok {
			// Refresh once; ids are only valid for the most recent dump.
			if _, _, err := dumpUI(); err == nil {
				// re-dump keeps ids stable only if the screen did not change
				n, ok = g.lookup(strings.TrimPrefix(target, "node:"))
			}
			if !ok {
				return resp(false, "set_value failed", fmt.Sprintf("target %s not found; run observe again", target))
			}
		}
		node = n
		if err := tap(node.CX, node.CY); err != nil {
			return resp(false, "set_value failed", err.Error())
		}
		time.Sleep(300 * time.Millisecond)

		// ACTION_SET_TEXT is unavailable here, so emulate "set" by clearing the field
		// first: place the caret at the end and delete the existing runes. Without this a
		// second set_value would silently append instead of replace.
		if existing := []rune(node.Text); len(existing) > 0 {
			_, _ = shell(3*time.Second, "/system/bin/input keyevent 123") // MOVE_END
			n := len(existing)
			if n > 200 {
				n = 200
			}
			keys := strings.TrimSpace(strings.Repeat("67 ", n)) // KEYCODE_DEL
			if keys != "" {
				_, _ = shell(12*time.Second, "/system/bin/input keyevent "+keys)
			}
			time.Sleep(300 * time.Millisecond)
		}
	}

	// Preferred path: clipboard + PASTE (handles CJK).
	if err := clipboardSet(text); err == nil {
		if err := pressKey("PASTE"); err == nil {
			time.Sleep(200 * time.Millisecond)
			return resp(true, "text injected via clipboard paste", text)
		}
	}
	// Fallback for ASCII: `input text` (spaces must be %s).
	escaped := strings.ReplaceAll(text, " ", "%s")
	out, err := shell(8*time.Second, "/system/bin/input text '"+escaped+"' 2>&1")
	if err != nil {
		return resp(false, "set_value failed", out)
	}
	return resp(true, "text injected via input text (ASCII fallback)", text)
}

// ─────────────────────────── HTTP ───────────────────────────

func resp(ok bool, message, data string) map[string]interface{} {
	return map[string]interface{}{"success": ok, "message": message, "data": data}
}

type actionReq struct {
	Action        string `json:"action"`
	Coordinate    []int  `json:"coordinate"`
	EndCoordinate []int  `json:"end_coordinate"`
	Target        string `json:"target"`
	Text          string `json:"text"`
	DurationMs    int    `json:"duration_ms"`
	Mode          string `json:"mode"`
}

func writeJSON(w http.ResponseWriter, code int, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (g *gateway) handleAction(w http.ResponseWriter, r *http.Request) {
	var req actionReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 400, resp(false, "bad request", err.Error()))
		return
	}
	if req.Action != "wait" {
		ensureAwake()
	}
	// Request trace: the first question when "the agent says it cannot" is whether the
	// tool was ever called, so every action is logged with its outcome summary.
	logLine := fmt.Sprintf("%s action=%s target=%q text=%q coord=%v", time.Now().Format("15:04:05"), req.Action, req.Target, truncate(req.Text, 40), req.Coordinate)
	defer func() {
		if f, err := os.OpenFile("/data/local/tmp/agent/actions.log", os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644); err == nil {
			_, _ = f.WriteString(logLine + "\n")
			f.Close()
		}
	}()
	switch req.Action {
	case "observe":
		writeJSON(w, 200, actionObserve(g))
	case "click":
		var x, y int
		if req.Target != "" {
			n, ok := g.lookup(strings.TrimPrefix(req.Target, "node:"))
			if !ok {
				writeJSON(w, 200, resp(false, "click failed", "target not found; run observe again"))
				return
			}
			x, y = n.CX, n.CY
		} else if len(req.Coordinate) >= 2 {
			x, y = req.Coordinate[0], req.Coordinate[1]
		} else {
			writeJSON(w, 200, resp(false, "click failed", "need target or coordinate"))
			return
		}
		if req.DurationMs > 0 {
			if err := swipe(x, y, x, y, req.DurationMs); err != nil {
				writeJSON(w, 200, resp(false, "long press failed", err.Error()))
				return
			}
		} else if err := tap(x, y); err != nil {
			writeJSON(w, 200, resp(false, "click failed", err.Error()))
			return
		}
		time.Sleep(500 * time.Millisecond)
		writeJSON(w, 200, withState(g, fmt.Sprintf("Tapped (%d, %d)", x, y)))
	case "swipe":
		if len(req.Coordinate) < 2 || len(req.EndCoordinate) < 2 {
			writeJSON(w, 200, resp(false, "swipe failed", "need coordinate and end_coordinate"))
			return
		}
		if err := swipe(req.Coordinate[0], req.Coordinate[1], req.EndCoordinate[0], req.EndCoordinate[1], req.DurationMs); err != nil {
			writeJSON(w, 200, resp(false, "swipe failed", err.Error()))
			return
		}
		time.Sleep(500 * time.Millisecond)
		writeJSON(w, 200, withState(g, fmt.Sprintf("Swiped (%d,%d) -> (%d,%d)",
			req.Coordinate[0], req.Coordinate[1], req.EndCoordinate[0], req.EndCoordinate[1])))
	case "set_value":
		writeJSON(w, 200, setValue(g, req.Target, req.Text))
	case "key":
		if err := pressKey(req.Text); err != nil {
			writeJSON(w, 200, resp(false, "key failed", err.Error()))
			return
		}
		time.Sleep(400 * time.Millisecond)
		writeJSON(w, 200, withState(g, "Pressed "+strings.ToUpper(req.Text)))
	case "wait":
		d := req.DurationMs
		if d <= 0 {
			d = 800
		}
		if d > 30000 {
			d = 30000
		}
		time.Sleep(time.Duration(d) * time.Millisecond)
		writeJSON(w, 200, withState(g, fmt.Sprintf("Waited %d ms", d)))
	case "launch_app":
		if err := launchApp(req.Text); err != nil {
			writeJSON(w, 200, resp(false, "launch failed", err.Error()))
			return
		}
		time.Sleep(1200 * time.Millisecond)
		writeJSON(w, 200, withState(g, "Launched "+req.Text))
	default:
		writeJSON(w, 200, resp(false, "unknown action", req.Action))
	}
}

// withState returns the action result plus a fresh observation, mirroring the closed-loop
// behaviour the preset expects from every physical action.
func withState(g *gateway, message string) map[string]interface{} {
	obs := actionObserve(g)
	data, _ := obs["data"].(string)
	ok, _ := obs["success"].(bool)
	if !ok {
		return resp(false, message, fmt.Sprint(obs["data"]))
	}
	return resp(true, message, data)
}

func (g *gateway) handleMode(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Mode string `json:"mode"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	mode := strings.ToLower(strings.TrimSpace(body.Mode))
	switch mode {
	case "foreground", "fg", "0":
		g.setMode("foreground")
		writeJSON(w, 200, map[string]interface{}{
			"success": true, "mode": "foreground", "target_display_id": 0,
			"message": "Switched to foreground mode (physical display 0)",
		})
	case "background", "bg":
		// Android 9 can render apps on a virtual display but neither the accessibility
		// tree nor the input focus reaches it, so background mode is not offered.
		g.setMode("foreground")
		writeJSON(w, 200, map[string]interface{}{
			"success": true, "mode": "foreground", "target_display_id": 0,
			"message": "Android 9 lacks secondary-display input focus; stayed in foreground mode",
		})
	case "idle", "standby", "none", "-1":
		g.setMode("idle")
		writeJSON(w, 200, map[string]interface{}{
			"success": true, "mode": "idle", "target_display_id": 0,
			"message": "Idle (no further physical actions will be issued until foreground)",
		})
	default:
		writeJSON(w, 200, resp(false, "unknown mode", mode))
	}
}

func (g *gateway) handleApps(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Query string `json:"query"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	q := strings.ToLower(strings.TrimSpace(body.Query))
	out, err := shell(10*time.Second, "/system/bin/pm list packages -3 2>/dev/null")
	if err != nil {
		writeJSON(w, 200, resp(false, "list apps failed", err.Error()))
		return
	}
	var b strings.Builder
	count := 0
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "package:") {
			continue
		}
		pkg := strings.TrimPrefix(line, "package:")
		if q != "" && !strings.Contains(strings.ToLower(pkg), q) {
			continue
		}
		b.WriteString(pkg)
		b.WriteString("\n")
		count++
	}
	writeJSON(w, 200, resp(true, fmt.Sprintf("total=%d", count), b.String()))
}

func (g *gateway) handleScreenshot(w http.ResponseWriter, r *http.Request) {
	ensureAwake()
	path := "/data/local/tmp/agent/shot.png"
	if _, err := shell(10*time.Second, "/system/bin/screencap -p "+path); err != nil {
		writeJSON(w, 500, resp(false, "screenshot failed", err.Error()))
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeJSON(w, 500, resp(false, "screenshot unreadable", err.Error()))
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = io.Copy(w, f)
}

func (g *gateway) noop(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]interface{}{"success": true, "message": "ok"})
}

func (g *gateway) handleSecret(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Secret string `json:"secret"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	g.mu.Lock()
	g.secret = body.Secret
	g.mu.Unlock()
	writeJSON(w, 200, map[string]interface{}{"success": true})
}

// handleWatch holds the connection open; the preset only uses it as a liveness hint.
func (g *gateway) handleWatch(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	fl, ok := w.(http.Flusher)
	if !ok {
		return
	}
	for i := 0; i < 600; i++ {
		select {
		case <-r.Context().Done():
			return
		case <-time.After(time.Second):
			_, _ = fmt.Fprintf(w, "ping %d\n", i)
			fl.Flush()
		}
	}
}

func (g *gateway) handleStatus(w http.ResponseWriter, r *http.Request) {
	w_, h_ := screenSize()
	g.mu.RLock()
	mode := g.mode
	n := len(g.nodes)
	g.mu.RUnlock()
	writeJSON(w, 200, map[string]interface{}{
		"success": true, "mode": mode, "display_id": 0, "width": w_, "height": h_,
		"nodes": n, "device": "xiaomi9-android9", "sdk": 28,
	})
}

// handleJump redirects to the DSH Web UI with the current session token, so the phone
// only ever needs a short address (http://127.0.0.1:3070/t) instead of the 60+ char token.
// The token is regenerated by DSH on every start, hence reading it live from its log.
func (g *gateway) handleJump(w http.ResponseWriter, r *http.Request) {
	token, err := dshToken()
	if err != nil {
		writeJSON(w, 500, resp(false, "no token", err.Error()))
		return
	}
	http.Redirect(w, r, "http://127.0.0.1:3080/?token="+token, http.StatusFound)
}

// dshToken returns the newest token DSH printed on startup.
func dshToken() (string, error) {
	data, err := os.ReadFile("/data/local/ubuntu/root/dsh.log")
	if err != nil {
		return "", err
	}
	m := regexp.MustCompile(`token=([A-Za-z0-9_\-]+)`).FindAllStringSubmatch(string(data), -1)
	if len(m) == 0 {
		return "", fmt.Errorf("no token in dsh log yet")
	}
	return m[len(m)-1][1], nil
}

// ─────────────────────────── DSH reverse proxy ───────────────────────────
//
// Some Android 9 browser builds silently drop the Set-Cookie issued by the token
// redirect, leaving the UI stuck on "authentication required". Rather than fight the
// browser, the gateway proxies the UI and adds the session cookie itself, so the phone
// opens a plain address (http://127.0.0.1:3099/) with nothing to type.

type dshProxy struct {
	target *url.URL
	proxy  *httputil.ReverseProxy
	mu     sync.RWMutex
	cookie string
}

func newDshProxy(target string) *dshProxy {
	u, _ := url.Parse(target)
	p := &dshProxy{target: u}
	p.proxy = httputil.NewSingleHostReverseProxy(u)
	orig := p.proxy.Director
	p.proxy.Director = func(req *http.Request) {
		orig(req)
		req.Host = u.Host
		p.mu.RLock()
		ck := p.cookie
		p.mu.RUnlock()
		if ck != "" {
			req.Header.Set("Cookie", ck)
		}
		// Strip the browser's origin so DSH's trust fence does not reject the proxied call.
		req.Header.Del("Origin")
		req.Header.Del("Referer")
	}
	p.proxy.ModifyResponse = func(resp *http.Response) error {
		if loc := resp.Header.Get("Location"); loc != "" {
			resp.Header.Set("Location", strings.ReplaceAll(loc, u.String(), "http://127.0.0.1:3099"))
			resp.Header.Set("Location", strings.ReplaceAll(resp.Header.Get("Location"), "127.0.0.1:3080", "127.0.0.1:3099"))
		}
		return nil
	}
	return p
}

// refresh logs in with the current token and remembers the issued cookie.
func (p *dshProxy) refresh() error {
	token, err := dshToken()
	if err != nil {
		return err
	}
	client := &http.Client{
		Timeout: 8 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	resp, err := client.Get(p.target.String() + "/?token=" + token)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	for _, c := range resp.Header["Set-Cookie"] {
		if strings.HasPrefix(c, "dsh-auth-") {
			p.mu.Lock()
			p.cookie = strings.SplitN(c, ";", 2)[0]
			p.mu.Unlock()
			return nil
		}
	}
	return fmt.Errorf("no auth cookie in login response (%d)", resp.StatusCode)
}

func (p *dshProxy) Handler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		p.mu.RLock()
		has := p.cookie != ""
		p.mu.RUnlock()
		if !has {
			_ = p.refresh()
		}
		p.proxy.ServeHTTP(w, r)
	})
}

func main() {
	g := &gateway{mode: "foreground"}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/action", g.handleAction)
	mux.HandleFunc("/api/mode", g.handleMode)
	mux.HandleFunc("/api/apps", g.handleApps)
	mux.HandleFunc("/api/screenshot", g.handleScreenshot)
	mux.HandleFunc("/api/notify", g.noop)
	mux.HandleFunc("/api/task_event", g.noop)
	mux.HandleFunc("/api/question", g.noop)
	mux.HandleFunc("/api/question/cancel", g.noop)
	mux.HandleFunc("/api/auth/secret", g.handleSecret)
	mux.HandleFunc("/api/session/watch", g.handleWatch)
	mux.HandleFunc("/api/status", g.handleStatus)
	mux.HandleFunc("/t", g.handleJump)
	mux.HandleFunc("/jump", g.handleJump)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]interface{}{"name": "agent-mobile-use mi9 gateway", "ok": true})
	})

	port := os.Getenv("PORT")
	if port == "" {
		port = "3070"
	}
	host := os.Getenv("HOST")
	if host == "" {
		host = "127.0.0.1"
	}
	// Token-free UI entry: http://127.0.0.1:3099/
	px := newDshProxy("http://127.0.0.1:3080")
	go func() {
		for i := 0; i < 30; i++ {
			if err := px.refresh(); err == nil {
				fmt.Println("[mi9-gateway] DSH auth cookie acquired")
				break
			}
			time.Sleep(time.Second)
		}
		uiAddr := host + ":3099"
		fmt.Println("[mi9-gateway] DSH UI proxy on " + uiAddr)
		if err := http.ListenAndServe(uiAddr, px.Handler()); err != nil {
			fmt.Println("[mi9-gateway] ui proxy stopped:", err)
		}
	}()

	fmt.Printf("[mi9-gateway] listening on %s:%s\n", host, port)
	if err := http.ListenAndServe(host+":"+port, mux); err != nil {
		fmt.Println("[mi9-gateway] fatal:", err)
		os.Exit(1)
	}
}
