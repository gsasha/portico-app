package tailnet

import (
	"strings"
	"testing"
)

// The table format is the contract with the Java side (TailnetInterfaces.kt,
// ported from the intercom app's Interfaces.java).

func TestParseInterfacesFillsAltAddrs(t *testing.T) {
	table := "lo\t1\t65536\t37\t127.0.0.1/8,::1/128\n" +
		"wlan0\t30\t1500\t51\t192.168.1.23/24,fe80::1c2b:3cff:fe4d:5e6f/64\n" +
		"rmnet_data0\t12\t1500\t41\t\n"
	ifs, err := parseInterfaces(table)
	if err != nil {
		t.Fatal(err)
	}
	if len(ifs) != 3 {
		t.Fatalf("parsed %d interfaces, want 3", len(ifs))
	}
	w := ifs[1]
	if w.Name != "wlan0" || w.Index != 30 || w.MTU != 1500 || int(w.Flags) != 51 {
		t.Fatalf("wlan0 parsed as %+v", w.Interface)
	}
	// AltAddrs, not nil: nil makes netmon fall back to the blocked syscall.
	if len(w.AltAddrs) != 2 || !strings.HasPrefix(w.AltAddrs[0].String(), "192.168.1.23/24") {
		t.Fatalf("wlan0 AltAddrs = %v", w.AltAddrs)
	}
	if ifs[2].AltAddrs != nil && len(ifs[2].AltAddrs) != 0 {
		t.Fatalf("rmnet AltAddrs = %v", ifs[2].AltAddrs)
	}
}

func TestParseInterfacesRefusesAnEmptyTable(t *testing.T) {
	if _, err := parseInterfaces("  \n"); err == nil {
		t.Fatal("an empty table must be an error, not 'no network'")
	}
	if _, err := parseInterfaces("garbage\n"); err == nil {
		t.Fatal("an unparseable table must be an error")
	}
}

func TestSetInterfacesFeedsTheGetter(t *testing.T) {
	SetInterfaces("wlan0\t30\t1500\t51\t192.168.1.77/16\n")
	s, _ := ifaceLines.Load().(string)
	ifs, err := parseInterfaces(s)
	if err != nil || len(ifs) != 1 || ifs[0].Name != "wlan0" {
		t.Fatalf("%v %v", ifs, err)
	}
}
