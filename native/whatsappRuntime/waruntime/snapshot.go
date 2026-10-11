package waruntime

import (
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"strings"
)

type sqliteDump struct {
	Version int          `json:"v"`
	Tables  []sqliteTable `json:"t"`
}

type sqliteTable struct {
	Name    string     `json:"n"`
	Columns []string   `json:"c"`
	Rows    [][]string `json:"r"`
}

func dumpSQL(db *sql.DB) ([]byte, error) {
	names, err := tableNames(db)
	if err != nil {
		return nil, err
	}
	out := sqliteDump{Version: 1}
	for _, name := range names {
		cols, err := tableColumns(db, name)
		if err != nil {
			return nil, err
		}
		quoted := quoteIdent(name)
		rows, err := db.Query("SELECT * FROM " + quoted)
		if err != nil {
			return nil, err
		}
		table := sqliteTable{Name: name, Columns: cols}
		for rows.Next() {
			raw := make([]any, len(cols))
			ptrs := make([]any, len(cols))
			for i := range raw {
				ptrs[i] = &raw[i]
			}
			if err := rows.Scan(ptrs...); err != nil {
				rows.Close()
				return nil, err
			}
			encoded := make([]string, len(cols))
			for i, v := range raw {
				encoded[i] = encodeCell(v)
			}
			table.Rows = append(table.Rows, encoded)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
		out.Tables = append(out.Tables, table)
	}
	return json.Marshal(out)
}

func restoreSQL(db *sql.DB, blob []byte) error {
	var dump sqliteDump
	if err := json.Unmarshal(blob, &dump); err != nil {
		return err
	}
	if _, err := db.Exec("PRAGMA foreign_keys=OFF"); err != nil {
		return err
	}
	defer db.Exec("PRAGMA foreign_keys=ON")
	for i := len(dump.Tables) - 1; i >= 0; i-- {
		if _, err := db.Exec("DELETE FROM " + quoteIdent(dump.Tables[i].Name)); err != nil {
			// Table may not exist yet if schema is newer; ignore missing.
			if !strings.Contains(strings.ToLower(err.Error()), "no such table") {
				return err
			}
		}
	}
	for _, table := range dump.Tables {
		if len(table.Rows) == 0 {
			continue
		}
		placeholders := strings.Repeat("?,", len(table.Columns))
		placeholders = strings.TrimSuffix(placeholders, ",")
		colList := make([]string, len(table.Columns))
		for i, c := range table.Columns {
			colList[i] = quoteIdent(c)
		}
		q := "INSERT INTO " + quoteIdent(table.Name) + " (" + strings.Join(colList, ",") + ") VALUES (" + placeholders + ")"
		for _, row := range table.Rows {
			args := make([]any, len(row))
			for i, cell := range row {
				args[i] = decodeCell(cell)
			}
			if _, err := db.Exec(q, args...); err != nil {
				return fmt.Errorf("restore %s: %w", table.Name, err)
			}
		}
	}
	return nil
}

func tableNames(db *sql.DB) ([]string, error) {
	rows, err := db.Query(`SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var names []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		names = append(names, name)
	}
	return names, rows.Err()
}

func tableColumns(db *sql.DB, table string) ([]string, error) {
	rows, err := db.Query("PRAGMA table_info(" + quoteIdent(table) + ")")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var cols []string
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt any
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return nil, err
		}
		cols = append(cols, name)
	}
	return cols, rows.Err()
}

func quoteIdent(name string) string {
	return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
}

func encodeCell(v any) string {
	if v == nil {
		return ""
	}
	switch t := v.(type) {
	case []byte:
		return "b64:" + base64.StdEncoding.EncodeToString(t)
	case string:
		return "s:" + t
	case int64:
		return fmt.Sprintf("i:%d", t)
	case float64:
		return fmt.Sprintf("f:%v", t)
	default:
		return "s:" + fmt.Sprint(t)
	}
}

func decodeCell(cell string) any {
	if cell == "" {
		return nil
	}
	switch {
	case strings.HasPrefix(cell, "b64:"):
		b, _ := base64.StdEncoding.DecodeString(strings.TrimPrefix(cell, "b64:"))
		return b
	case strings.HasPrefix(cell, "s:"):
		return strings.TrimPrefix(cell, "s:")
	case strings.HasPrefix(cell, "i:"):
		var n int64
		fmt.Sscanf(strings.TrimPrefix(cell, "i:"), "%d", &n)
		return n
	case strings.HasPrefix(cell, "f:"):
		var n float64
		fmt.Sscanf(strings.TrimPrefix(cell, "f:"), "%f", &n)
		return n
	default:
		return cell
	}
}
