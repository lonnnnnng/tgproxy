package logbus

import "testing"

func TestBusSinceAndLimit(t *testing.T) {
	b := New(2)
	b.Add("info", "one")
	b.Add("success", "two %d", 2)
	entries, seq := b.Since(0)
	if len(entries) != 2 || seq != 2 {
		t.Fatalf("entries=%v seq=%d", entries, seq)
	}
	b.Add("error", "three")
	entries, seq = b.Since(seq)
	if len(entries) != 1 || entries[0].Message != "three" || seq != 3 {
		t.Fatalf("entries=%v seq=%d", entries, seq)
	}
	all, _ := b.Since(0)
	if len(all) != 2 || all[0].Message != "two 2" {
		t.Fatalf("bounded=%v", all)
	}
}
