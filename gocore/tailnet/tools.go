//go:build tools

// gomobile needs golang.org/x/mobile/bind in the module graph, but no file in
// this package imports it, so `go mod tidy` would remove it and bind.sh would
// fail with "missing golang.org/x/mobile dependency". This blank import under
// a build tag nothing builds keeps it required without adding it to the
// binary. (Same trick as the intercom app's tsmobile/tools.go.)
package tailnet

import _ "golang.org/x/mobile/bind"
