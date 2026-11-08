package config

import "testing"

func TestInvalidChunkConfiguration(t *testing.T) {
	for _, chunk := range []int64{0, -1, 4097, 12288, 2 << 20} {
		c := Defaults()
		c.ChunkSize = chunk
		if c.Validate() == nil {
			t.Fatalf("accepted chunk=%d", chunk)
		}
	}
	for _, chunk := range []int64{4096, 256 << 10, 512 << 10, 1 << 20} {
		c := Defaults()
		c.ChunkSize = chunk
		if err := c.Validate(); err != nil {
			t.Fatal(err)
		}
	}
}
