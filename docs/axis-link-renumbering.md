# Axis link renumbering (Row axis): rules and evidence

Code: `daemon/.../service/AxisLinkRenumberer.java`, enabled per `axisLinks` item with `"renumber": "true"`.
Tests: `AxisLinkRenumbererTest` with fixtures in `daemon/src/test/resources/axislink/`.
Evidence so far: **two** Studio pairs:
1. `MONCOMP_DC` (template `/Users/test`, 27 segments, inserted at position 0, kept segment IDs `34,52,7,57,61,58,59,56,64,8,9`).
2. `MONCOMP_NI_JG` (template `/Users/test2`, 15 segments, inserted at position 5, kept segment IDs `0,1,2,3,4,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,52,53,54,55,38,39,40,41,42,43,44,45,46,47`).

| # | Rule | Evidence | Status |
|---|---|---|---|
| R1 | `SEGMENTSET` link tag before the axes; row segments not in the keep list are removed; `MAX_SEGMENT_ID` unchanged | DC pair ($P=0$) & NI pair ($P=5$), exact | Verified across 2 pairs |
| R2 | `ROW_COUNT` = rendered rows of the kept own segments (DC: 41 to 14; NI: 55 to 40). Template rows are not counted | DC pair & NI pair | Verified across 2 pairs |
| R3 | `ROW_PROP` / `STYLE_IDX`: one entry per own row, renumbered 0..n-1 | DC pair & NI pair, exact | Verified across 2 pairs |
| R3b | **3-Row Boundary Rule.** At insertion position $P$, rows from $P$ to $P+2$ copy styles/props from original rows $P$ to $P+2$, while rows from $P+3$ onward follow `keptRows`. In DC ($P=0$): rows 0, 1, 2 copy from orig 0, 1, 2. In NI ($P=5$): rows 5, 6, 7 copy from orig 5, 6, 7. Configured via `studio_head_rows` (default 3; setting 0 follows rows strictly) | DC & NI pairs, exact match in both | Verified across 2 pairs |
| R4 | `CELL_STYLE` and `COND_FORMAT`: `keep_style` seeded from `new_si`, `keep_cf` seeded from `new_si` CFs plus CFs referenced by original `keptRows`. Fixed-point closure over `CELL_STYLE.COND_FORMAT` and `COMP_CONDITION.STYLE_INDEX`, renumbered in original order. Template styles are **not** merged in | DC: 124 to 108 styles, 23 to 22 formats; NI: 105 to 98 styles, 28 to 28 formats, exact | Verified across 2 pairs |
| R5 | `GRIDCELL ROW` of kept rows is renumbered to the new own row; negative header rows ($-1 \dots -\text{colLayers}$) unchanged. A cell on a dropped row fails the call | DC pair (27 to 0, 34 to 7); NI pair (header rows $-3, -2, -1$ preserved, row 0 preserved) | Verified across 2 pairs |
| R6 | Row references in `DERIVEDEXPRESSION` (`[n]`, `[Col,n]`): targets before $P$ shift by $0$; targets at or after $P$ shift by $+(T - D)$ where $T$ is template segments and $D$ is dropped segments. When $T = D$ (NI: 15 dropped replaced by 15), shift is 0 | DC pair: $+27$; NI pair: $+0$ (e.g. `[A,10]` preserved) | Verified across 2 pairs |
| R7 | Studio adds `EXPAND_PLACEMENT="0"` to the grid and normalizes `METADATAEXPRESSION HEADING` `GetHeading(...)` removing quotes and spacing arguments. Supports numeric col conversion (DC: `F` $\to$ `6`) or letter preservation (NI: `F`), switchable via `numeric_heading_col` | DC pair & NI pair | Verified across 2 pairs |
| - | `FORMAT_SEGMENT`: defaults to template segment count (DC: 27), or explicitly configured via `format_segment` (NI: "4") | DC pair & NI pair | Verified across 2 pairs |

## Things the XML cannot tell us

* **Rows per segment.** `ROW_COUNT` is often larger than segment count:
  - In DC: 41 rows vs 38 segments (segment 8 renders 4 rows, `segment_rows="8:4"`).
  - In NI: 55 rows vs 52 segments (3 segments render 2 rows, `segment_rows="21:2,22:2,23:2"`).
  `segment_rows` is required whenever segments do not add up to `ROW_COUNT`.
* The DC pair leaves dangling formula references (`[54]`, `-[61]`, `[A,28]`). The self-checker reports these as
  `formula_references_point_to_existing_rows` with `ok: false` and fails the call only with `strict_refs: "true"`. The
  other checks (row counts/props/style_idx, `GRIDCELL ROW` range, contiguous indexes/links) always fail the call.

## Refused

Negative position ($P < 0$), Column axis, an already linked axis, templates whose segment count differs from their `STYLE_IDX` count,
unknown keep IDs, a keep list that drops a row carrying `GRIDCELL` overrides.

## Next

A third independent golden pair (e.g. `MONCOMP_BC` or a column pair) to fulfill the three-pair acceptance requirement.
