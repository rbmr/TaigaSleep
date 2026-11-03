package version

import (
	"bufio"
	"os"
	"strconv"
	"strings"
	"testing"
)

// TestMatchesVersionProperties keeps the constants the binary reports and the
// file the release tooling reads from drifting apart.
func TestMatchesVersionProperties(t *testing.T) {
	values := readVersionProperties(t)

	if got := values["version"]; got != Number {
		t.Errorf("version.properties has version %q, version.Number is %q", got, Number)
	}

	api, err := strconv.Atoi(values["api"])
	if err != nil {
		t.Fatalf("version.properties: api is not an integer: %v", err)
	}
	if api != API {
		t.Errorf("version.properties has api %d, version.API is %d", api, API)
	}
}

func readVersionProperties(t *testing.T) map[string]string {
	t.Helper()

	// The package directory is backend/api/internal/version, so the file sits
	// three levels up.
	file, err := os.Open("../../../version.properties")
	if err != nil {
		t.Fatalf("open backend/version.properties: %v", err)
	}
	defer file.Close()

	values := map[string]string{}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			t.Fatalf("version.properties: cannot parse %q", line)
		}
		values[strings.TrimSpace(key)] = strings.TrimSpace(value)
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("read version.properties: %v", err)
	}
	return values
}
