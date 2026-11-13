# Axis link renumbering (Row axis): rules and evidence

Code: `daemon/.../service/AxisLinkRenumberer.java`, enabled per `axisLinks` item with `"renumber": "true"`.
Tests: `AxisLinkRenumbererTest` with fixtures in `daemon/src/test/resources/axislink/`.
Evidence so far: **one** Studio pair, `MONCOMP_DC` (template `/Users/test`, 27 segments, inserted at position 0,
kept segment IDs `34,52,7,57,61,58,59,56,64,8,9`). Every rule below is therefore single-example.

| # | Rule | Evidence | Status |
|---|---|---|---|
| R1 | `SEGMENTSET` link tag before the axes; row segments not in the keep list are removed; `MAX_SEGMENT_ID` unchanged | DC pair, exact | single example |
| R2 | `ROW_COUNT` = rendered rows of the kept own segments (41 to 14). Template rows are not counted | DC pair | single example |
| R3 | `ROW_PROP` / `STYLE_IDX`: one entry per own row, renumbered 0..n-1 | DC pair, exact | single example |
| R3b | **Open.** Studio's first 3 own entries are copies of original rows 0, 1, 2, not of the kept rows 27, 28, 29. Rows 3..13 do follow the kept rows (orig 30..40). Option `studio_head_rows` (default 0 = follow the row; the DC golden test uses 3) | DC pair | **unexplained**, needs a second pair |
| R4 | `CELL_STYLE` and `COND_FORMAT`: keep only entries reachable from the kept `STYLE_IDX` rows (closure over `CELL_STYLE.COND_FORMAT` and `COMP_CONDITION.STYLE_INDEX`), renumber in original order. Template styles are **not** merged in | DC pair: 124 to 108 styles, 23 to 22 formats, exact | single example, but a strong fit (170 attribute values) |
| R5 | `GRIDCELL ROW` of kept rows is renumbered to the new own row; `-1`/`-2` unchanged. A cell on a dropped row fails the call | DC pair (27 to 0, 34 to 7) | the failure part is a safety rule, no example |
| R6 | Every row reference in a `DERIVEDEXPRESSION` (`[n]`, `[Col,n]`) is shifted by the template segment count (+27), whether or not the target row survives | DC pair: `[27]` to `[54]`, `-[34]` to `-[61]`, `[A,1]` to `[A,28]` | single example. Not separable from "+ number of deleted leading rows" (also 27) |
| R7 | Studio also adds `EXPAND_PLACEMENT="0"` to the grid and rewrites one `METADATAEXPRESSION HEADING` `GetHeading("Grid1",1,F,3)` to `GetHeading(Grid1, 1, 6, 3)`. Unrelated to linking; switch off with `studio_resave: false` | DC pair, 1 occurrence each | single example |
| - | `FORMAT_SEGMENT` defaults to the template segment count (27) when `format_segment` is not given | DC pair | single example |

Hypotheses from the brief: the +27 shift equals the template segment count (supported once); `ROW_COUNT` 14 counts only
the report's own rows (supported once); "template styles first" in the merged style list is **refuted** (styles are not
merged).

## Things the XML cannot tell us

* **Rows per segment.** `ROW_COUNT` (41) is larger than the segment count (38): segment ID 8 renders 4 rows. That is not
  readable from the XML, so `segment_rows` (`"8:4"`) is required whenever the rows do not add up to `ROW_COUNT`.
* The DC pair itself breaks the "every `[n]` points to an existing row" check: Studio leaves `[54]`, `-[61]` (the combined
  grid has 41 rows) and `[A,28]` (a row that was `[A,1]` before and is dropped). The check therefore reports these as
  `formula_references_point_to_existing_rows` with `ok: false` and fails the call only with `strict_refs: "true"`. The
  other three checks (row counts/props/style_idx, `GRIDCELL ROW` range, contiguous `COND_FORMAT`/`CELL_STYLE` indexes and
  links) always fail the call.

## Refused

Column axis, position other than 0, a template placed after existing rows, an already linked axis, templates whose segment
count differs from their `STYLE_IDX` count, unknown keep IDs, a keep list that drops a row carrying `GRIDCELL` overrides.

## Still needed

Golden pairs for `MONCOMP_NI` (and `NI_L1`, `NI_NIFO`, `BC`, `BC_L3`), including one with two templates and different
formulas, and a Column pair. The feature is **not done** until three independent pairs pass.
