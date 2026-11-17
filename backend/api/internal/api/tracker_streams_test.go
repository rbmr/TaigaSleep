package api

import (
	"net/http"
	"testing"
)

// A tracker may declare a stream of its own; the backend stores the file under the
// name the recording's meta.json uses, without knowing the codec.
func TestANewStreamFileIsAcceptedFromItsRecording(t *testing.T) {
	s := newServer(t)
	alice := s.tokens["alice"]
	id := "trackers"
	createRecording(s, alice, id, testMeta(id, "recording"))

	if st, _, body := appendChunk(s, alice, id, "ppg.log", 0, []byte("frames"), nil); st != 204 {
		t.Fatalf("append ppg.log = %d %s", st, body)
	}
	if st, _, body := appendChunk(s, alice, id, "hr.log", 0, []byte("beats"), nil); st != 204 {
		t.Fatalf("append hr.log = %d %s", st, body)
	}

	if st, _, body := s.call(http.MethodGet, "/v1/recordings/"+id+"/files", alice, nil, nil); st != 200 {
		t.Fatalf("list files = %d %s", st, body)
	}
	if got := s.expect(200, http.MethodGet, "/v1/recordings/"+id+"/files/ppg.log", alice, nil, nil); string(got) != "frames" {
		t.Fatalf("ppg.log = %q", got)
	}
}
