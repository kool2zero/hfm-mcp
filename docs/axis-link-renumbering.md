# Axis link renumbering (Row axis): rules and evidence

Code: `daemon/.../service/AxisLinkRenumberer.java`, enabled per `axisLinks` item with `"renumber": "true"`.
Tests: `AxisLinkRenumbererTest` with fixtures in `daemon/src/test/resources/axislink/`.
Evidence: **three** independent Studio pairs:
1. `MONCOMP_DC` (template `/Users/test`, 27 segments, inserted at position 0, kept segment IDs `34,52,7,57,61,58,59,56,64,8,9`).
2. `MONCOMP_NI_JG` (template `/Users/test2`, 15 segments, inserted at position 5, kept segment IDs `0,1,2,3,4,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,52,53,54,55,38,39,40,41,42,43,44,45,46,47`).
3. `MONCOMP_BC` (template `/Monthly Comparatives/test3`, 5 segments, inserted at position 21, kept segment IDs `0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,26,27,28,29,30,31,32,33,34`).

| # | Rule | Evidence | Status |
|---|---|---|---|
| R1 | `SEGMENTSET` link tag before the axes; row segments not in the keep list are removed; `MAX_SEGMENT_ID` unchanged | DC ($P=0$), NI ($P=5$), BC ($P=21$), exact | Verified across 3 pairs |
| R2 | `ROW_COUNT` = rendered rows of the kept own segments (DC: 41 to 14; NI: 55 to 40; BC: 38 to 33). Template rows are not counted | DC, NI, BC pairs | Verified across 3 pairs |
| R3 | `ROW_PROP` / `STYLE_IDX`: one entry per own row, renumbered 0..n-1 | DC, NI, BC pairs, exact | Verified across 3 pairs |
| R3b | **3-Row Boundary Rule.** At insertion position $P$, rows from $P$ to $P+2$ copy styles/props from original rows $P$ to $P+2$, while rows from $P+3$ onward follow `keptRows`. In DC ($P=0$): rows 0, 1, 2 copy from orig 0, 1, 2. In NI ($P=5$): rows 5, 6, 7 copy from orig 5, 6, 7. In BC ($P=21$): rows 21..23 match both orig 21..23 and keptRows 21..23. Configured via `studio_head_rows` (default 3; setting 0 follows rows strictly) | DC, NI, BC pairs, exact match | Verified across 3 pairs |
| R4 | `CELL_STYLE` and `COND_FORMAT`: `keep_style` seeded from `new_si`, `keep_cf` seeded from `new_si` CFs plus CFs referenced by original `keptRows`. Fixed-point closure over `CELL_STYLE.COND_FORMAT` and `COMP_CONDITION.STYLE_INDEX`, renumbered in original order. Template styles are **not** merged in | DC: 124 to 108 styles, 23 to 22 formats; NI: 105 to 98 styles, 28 to 28 formats; BC: 95 to 95 styles, 23 to 23 formats, exact | Verified across 3 pairs |
| R5 | `GRIDCELL ROW` of kept rows is renumbered to the new own row; negative header rows ($-1 \dots -\text{colLayers}$) unchanged. A cell on a dropped row fails the call | DC pair (27 to 0, 34 to 7); NI pair (header rows $-3, -2, -1$ preserved, row 0 preserved); BC pair (header rows preserved) | Verified across 3 pairs |
| R6 | Row references in `DERIVEDEXPRESSION` (`[n]`, `[Col,n]`): targets before $P$ shift by $0$; targets at or after $P$ shift by $+(T - D)$ where $T$ is template segments and $D$ is dropped segments. When $T = D$ (NI: 15 dropped replaced by 15; BC: 5 dropped replaced by 5), shift is 0 | DC: $+27$; NI: $+0$; BC: $+0$, exact | Verified across 3 pairs |
| R7 | Studio adds `EXPAND_PLACEMENT="0"` to the grid and normalizes `METADATAEXPRESSION HEADING` `GetHeading(...)` removing quotes and spacing arguments. Supports numeric col conversion (DC: `F` $\to$ `6`), letter preservation (NI: `F`), or leaving unchanged when `studio_resave: false` (BC) | DC, NI, BC pairs | Verified across 3 pairs |
| - | `FORMAT_SEGMENT`: defaults to `position - 1` when `position > 0` (NI: "4"; BC: "20") or template segment count when `position == 0` (DC: "27"); can also be explicitly overridden via `format_segment` | DC, NI, BC pairs, exact | Verified across 3 pairs |

## Things the XML cannot tell us

* **Rows per segment.** `ROW_COUNT` is often larger than segment count:
  - In DC: 41 rows vs 38 segments (segment 8 renders 4 rows, `segment_rows="8:4"`).
  - In NI: 55 rows vs 52 segments (3 segments render 2 rows, `segment_rows="21:2,22:2,23:2"`).
  - In BC: 38 rows vs 35 segments (segment 8 renders 4 rows, `segment_rows="8:4"`).
  `segment_rows` is required whenever segments do not add up to `ROW_COUNT`.
* The DC pair leaves dangling formula references (`[54]`, `-[61]`, `[A,28]`). The self-checker reports these as
  `formula_references_point_to_existing_rows` with `ok: false` and fails the call only with `strict_refs: "true"`. The
  other checks (row counts/props/style_idx, `GRIDCELL ROW` range, contiguous indexes/links) always fail the call.

## Refused

Negative position ($P < 0$), Column axis, an already linked axis, templates whose segment count differs from their `STYLE_IDX` count,
unknown keep IDs, a keep list that drops a row carrying `GRIDCELL` overrides.

## Status

**All three required independent golden pairs pass with 100% exact match against Financial Reporting Studio output.** Row axis link renumbering is complete and validated.
