// zipmod packs a Magisk module directory into a zip with forward-slash entry names.
// Windows archivers emit backslashes, which Magisk's installer cannot extract.
package main

import (
	"archive/zip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

func main() {
	if len(os.Args) < 3 {
		fmt.Println("usage: zipmod <srcDir> <out.zip>")
		os.Exit(1)
	}
	src, out := os.Args[1], os.Args[2]
	f, err := os.Create(out)
	if err != nil {
		panic(err)
	}
	defer f.Close()
	zw := zip.NewWriter(f)
	defer zw.Close()

	root := filepath.Clean(src)
	err = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return err
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		name := strings.ReplaceAll(rel, "\\", "/")
		hdr := &zip.FileHeader{Name: name, Method: zip.Deflate}
		hdr.SetMode(info.Mode())
		w, err := zw.CreateHeader(hdr)
		if err != nil {
			return err
		}
		in, err := os.Open(path)
		if err != nil {
			return err
		}
		defer in.Close()
		_, err = io.Copy(w, in)
		return err
	})
	if err != nil {
		panic(err)
	}
	fmt.Println("packed", out)
}
