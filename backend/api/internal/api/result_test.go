package api

import "testing"

func item(kind, id string) resultItem {
	value := 50.0
	item := resultItem{
		Kind: kind, ID: id, Label: id, Group: "test", Unit: "%", Value: &value,
		Explanation: "why", Sources: []string{"stages"},
	}
	if kind == "series" {
		item.Value = nil
		item.Series = []byte(`{"count":1}`)
	}
	if kind == "score" {
		item.Components = []scoreComponent{{ID: "part", Label: "Part", Weight: 100, Value: 50}}
	}
	return item
}

func TestValidateItems(t *testing.T) {
	valid := []resultItem{item("number", "a"), item("score", "b"), item("series", "c")}
	if err := validateItems(valid); err != nil {
		t.Fatalf("valid items: %v", err)
	}

	cases := map[string]func([]resultItem) []resultItem{
		"empty":           func([]resultItem) []resultItem { return nil },
		"no id":           func(items []resultItem) []resultItem { items[0].ID = ""; return items },
		"duplicate":       func(items []resultItem) []resultItem { items[1].ID = items[0].ID; return items },
		"unknown kind":    func(items []resultItem) []resultItem { items[0].Kind = "other"; return items },
		"number no value": func(items []resultItem) []resultItem { items[0].Value = nil; return items },
		"series no data":  func(items []resultItem) []resultItem { items[2].Series = nil; return items },
		"score too high":  func(items []resultItem) []resultItem { v := 101.0; items[1].Value = &v; return items },
		"score no parts":  func(items []resultItem) []resultItem { items[1].Components = nil; return items },
		"no source":       func(items []resultItem) []resultItem { items[0].Sources = nil; return items },
	}
	for name, mutate := range cases {
		items := append([]resultItem(nil), valid...)
		if err := validateItems(mutate(items)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}
