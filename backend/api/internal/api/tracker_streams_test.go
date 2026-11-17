package api

import (
	"net/http"
	"regexp"
	"testing"

	"github.com/rbmr/TaigaSleep/backend/api/internal/storage"
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

func TestTheFileNameSchemaMatchesTheValidator(t *testing.T) {
	pattern := ""
	for _, param := range fileParams() {
		if param.Name == "name" {
			pattern, _ = param.Schema["pattern"].(string)
		}
	}
	if pattern == "" {
		t.Fatal("the file-name parameter has no pattern")
	}
	expression := regexp.MustCompile(pattern)
	names := []string{
		"events.jsonl",
		"hr.log",
		"ppg.log",
		"gyro_2-x.log",
		"HR.log",
		"meta.json",
		"hr.log.gz",
		"a.log",
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.log",
		"../hr.log",
	}
	for _, name := range names {
		if got, want := expression.MatchString(name), storage.IsDataFile(name); got != want {
			t.Errorf("%q: schema says %v, validator says %v", name, got, want)
		}
	}
}
