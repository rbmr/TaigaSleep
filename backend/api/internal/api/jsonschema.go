package api

import (
	"encoding/json"
	"reflect"
	"strings"
)

// schemaReflector turns the API's Go request and response types into JSON
// Schema definitions for the generated OpenAPI document. Only the constructs
// those types use are supported: structs, pointers, slices, maps, strings,
// numbers, booleans, json.RawMessage and interface values.
//
// The document is derived from the types the handlers encode and decode, so a
// field rename shows up as a stale-spec test failure instead of silently wrong
// documentation.
type schemaReflector struct {
	defs map[string]any
}

var (
	rawMessageType = reflect.TypeOf(json.RawMessage(nil))
	marshalerType  = reflect.TypeOf((*json.Marshaler)(nil)).Elem()
)

// schemaTypes names the types a `schema:"Name"` struct tag can point at.
var schemaTypes = map[string]reflect.Type{
	"RecordingMeta": reflect.TypeOf(RecordingMeta{}),
}

func newSchemaReflector() *schemaReflector {
	return &schemaReflector{defs: map[string]any{}}
}

// schemaOf returns the JSON Schema for a type, registering named structs under
// components.schemas and referencing them with $ref.
func (r *schemaReflector) schemaOf(t reflect.Type) map[string]any {
	if t == nil || t == rawMessageType || t.Kind() == reflect.Interface {
		return map[string]any{}
	}
	if t.Kind() == reflect.Ptr {
		return map[string]any{
			"anyOf": []any{
				r.schemaOf(t.Elem()),
				map[string]any{"type": "null"},
			},
		}
	}
	if t.Implements(marshalerType) {
		return map[string]any{}
	}

	switch t.Kind() {
	case reflect.Struct:
		if t.Name() == "" {
			return r.objectSchema(t)
		}
		name := t.Name()
		if _, ok := r.defs[name]; !ok {
			// Register a placeholder first so recursive types terminate.
			r.defs[name] = map[string]any{}
			r.defs[name] = r.objectSchema(t)
		}
		return map[string]any{"$ref": "#/components/schemas/" + name}
	case reflect.Slice, reflect.Array:
		if t.Elem().Kind() == reflect.Uint8 {
			return map[string]any{"type": "string", "format": "byte"}
		}
		return map[string]any{"type": "array", "items": r.schemaOf(t.Elem())}
	case reflect.Map:
		if t.Key().Kind() == reflect.String {
			return map[string]any{"type": "object", "additionalProperties": r.schemaOf(t.Elem())}
		}
		return map[string]any{"type": "object"}
	case reflect.String:
		return map[string]any{"type": "string"}
	case reflect.Bool:
		return map[string]any{"type": "boolean"}
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		return map[string]any{"type": "integer"}
	case reflect.Float32, reflect.Float64:
		return map[string]any{"type": "number"}
	}
	return map[string]any{}
}

func (r *schemaReflector) objectSchema(t reflect.Type) map[string]any {
	properties := map[string]any{}
	required := []string{}

	for index := 0; index < t.NumField(); index++ {
		field := t.Field(index)
		if !field.IsExported() {
			continue
		}
		name, options := jsonField(field)
		if name == "-" {
			continue
		}
		if name == "" && field.Anonymous {
			embedded := field.Type
			if embedded.Kind() == reflect.Ptr {
				embedded = embedded.Elem()
			}
			if embedded.Kind() == reflect.Struct && embedded.Name() == "" {
				inner := r.objectSchema(embedded)
				for key, value := range inner["properties"].(map[string]any) {
					properties[key] = value
				}
				if names, ok := inner["required"].([]string); ok {
					required = append(required, names...)
				}
				continue
			}
		}
		if name == "" {
			name = field.Name
		}
		if documented, ok := field.Tag.Lookup("schema"); ok {
			// A raw JSON field whose shape is a Go type: document that type, since
			// the field is stored and returned verbatim.
			properties[name] = r.schemaOf(schemaTypes[documented])
		} else {
			properties[name] = r.schemaOf(field.Type)
		}
		if !hasJSONOption(options, "omitempty") && !hasJSONOption(options, "omitzero") {
			required = append(required, name)
		}
	}

	schema := map[string]any{"type": "object", "properties": properties}
	if len(required) > 0 {
		schema["required"] = required
	}
	return schema
}

func jsonField(field reflect.StructField) (string, string) {
	tag := field.Tag.Get("json")
	if tag == "" {
		return "", ""
	}
	name, options, _ := strings.Cut(tag, ",")
	return name, options
}

func hasJSONOption(options, want string) bool {
	for _, option := range strings.Split(options, ",") {
		if option == want {
			return true
		}
	}
	return false
}
