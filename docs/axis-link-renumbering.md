# Row-Axis Link Renumbering (`axisLinks` with `renumber: true`)

This document records the empirical evidence and rules implemented in `AxisLinkRenumberer` for replacing inline Row-axis segments in an Oracle Financial Reporting report (`FR_REPORT`, `.des`) with a live link (`<SEGMENTSET>`) to a Row & Column Template (`FR_ROW_COLUMN`, `.ros`).

## Verified Golden Pairs & Full-Book Runtime Validation

1. **Pair 1 (`POSITION="0"`)**: `MONCOMP_DC.original.xml` + `DC_rows_template.xml` -> `MONCOMP_DC_RF_V2.studio_linked.xml`
   - 38 original segments (41 rendered rows, segment `ID="8"` renders 4 rows); 27 segments moved to template `/Users/test`, 11 segments (14 rendered rows) kept.
2. **Pair 2 (`POSITION="5"`)**: `MONCOMP_NI_JG.xml` + `test2_rows_template.xml` -> `MONCOMP_NI_JG_LINKED.xml`
   - 52 original segments (55 rendered rows); 15 segments (`POSITION="5"`, original segments 5..19) moved to template `/Users/test2`, 37 segments (40 rendered rows) kept.
3. **Pair 3 (`POSITION="21"`)**: `MONCOMP_BC.xml` + `test3_rows_template.xml` -> `MONCOMP_BC_linked.xml`
   - 35 original segments (38 rendered rows); 5 segments (`POSITION="21"`, original segments 21..25) moved to template `/Monthly Comparatives/test3`, 30 segments (33 rendered rows) kept.
4. **Full 180-Page Book Runtime Validation (`MONCOMP_L3_LINKED`)**:
   - All 14 `MONCOMP_*_LINKED` reports and `MONCOMP_*_ROWS` templates verified across 180 rendered PDF pages against `MONCOMP_L3.pdf` (180/180 pages matching line counts, row structure, suppression, and dollar values).

## Transformation Rules

1. **R1 — `SEGMENTSETS` & `GRIDAXIS`**:
   - Dropped inline `<GRIDSEGMENT>` elements are removed from `<GRIDAXIS AXISNAME="Row">`.
   - `<SEGMENTSET AXIS="2" FORMAT_PRECEDENCE="1" FORMAT_SEGMENT="..." FORMULA_PRECEDENCE="1" OBJECT_LINKNAME="..." POSITION="..."/>` is inserted into `<SEGMENTSETS>`.
   - `FORMAT_SEGMENT` defaults to `position - 1` when `position > 0` (the row immediately preceding the link) and `tSegs` (the template segment count) when `position == 0`.
2. **R2 — `ROW_COUNT`**:
   - `GRIDOBJECT/@ROW_COUNT` is set to `n` (the rendered row count of the kept inline segments).
3. **R3 — `ROW_PROP` & `STYLE_IDX`**:
   - Renumbered `0 .. n-1`.
   - For index `i < min(n, position + studio_head_rows)`, properties are cloned from original row `i`; for `i >= boundary`, properties are cloned from `keptRows.get(i)`.
4. **R4 — `CELL_STYLE` & `COND_FORMAT` Garbage Collection**:
   - Only `<CELL_STYLE>` and `<COND_FORMAT>` entries reachable from the kept `STYLE_IDX` rows (and `keptRows` conditional formats) are retained and renumbered contiguously from `0`.
5. **R5 — `GRIDCELL` Row Renumbering**:
   - `<GRIDCELL ROW="r">` for kept rows (`r >= 0`) are renumbered to their new index `0 .. n-1`; header cells (`r < 0`) are unchanged.
6. **R6 — `DERIVEDEXPRESSION` Row References (`[n]` and `[Col,n]`)**:
   - At runtime, Financial Reporting expands the linked `<SEGMENTSET>` at `POSITION` *before* evaluating `<DERIVEDEXPRESSION>` formulas. Therefore, for targets `t >= position`, the runtime formula shift is `tSegs - droppedSegments` (which is **`0`** when replacing `N` inline segments with an `N`-segment template, regardless of whether `position == 0` or `position > 0`).
   - When `studio_shift_formulas: true` (`studioInsertBeforeDeleteShift`) is set, targets `t >= position` are shifted by `+tSegs` to reproduce what Studio saves when a user inserts a linked template at row 0 before deleting the original inline rows (as in `MONCOMP_DC_RF_V2.studio_linked.xml`).
7. **R7 — Studio Re-save Normalization**:
   - Adds `EXPAND_PLACEMENT="0"` if missing on `GRIDOBJECT` and normalizes `GetHeading("Grid1",1,F,3)` spacing/column formatting when `studio_resave` is enabled.

## `FR_ROW_COLUMN` (`.ros`) Template Rules

- **`SAVED_PROPS` & Per-Row Suppression (`<SEGMENT_PROP>`)**:
  - `HIDE_ALWAYS="1"` on `<SEGMENT_PROP SEGMENT="i"/>` is always honored per segment.
  - With `SAVED_PROPS="0000"`, Financial Reporting ignores `HEIGHT` and `SUPPRESSION` on `<SEGMENT_PROP>` and inherits Row Height and Suppression for all linked rows from the report's `FORMAT_SEGMENT` ("Inherit from") row.
  - With `SAVED_PROPS="1111"`, Financial Reporting honors per-segment `HEIGHT` and `SUPPRESSION` (`"-1"` If Missing, `"-2"` If Zero, `"-3"` If Zero or Missing) on `<SEGMENT_PROP>`.
- **Template-Relative Formula References (`<DERIVEDEXPRESSION>`)**:
  - 1-based `[row]` references inside `<DERIVEDEXPRESSION>` in a `.ros` template are relative to the template (`1 .. tSegs`). When extracting rows starting at 0-based `POSITION="P"` from an unlinked report, shift `[row]` references inside the extracted template segments by **`-P`** (`[row - P]`) and preserve `HEADING="..."` on `<DERIVEDEXPRESSION>`, because FR adds `+P` at runtime when the template is linked at `POSITION="P"`.
- **`<STYLE_IDX>` & `<CELL_STYLE>` Handling by Link Position**:
  - **For Links at `POSITION > 0` (`BC`, `BRS`, `NI`, `OI`, `FX`, etc.)**:
    - Omit `<CELL_STYLE>` and `<STYLE_IDX>` entirely from the `.ros` Row Template (keeping only `<SEGMENT_PROP>`, `<GRIDLAYER>`, and `<GRIDSEGMENT>`). Financial Reporting automatically applies the parent `.des` grid's column `<STYLE_IDX>` and `<CELL_STYLE>` formatting across all 21 columns of the linked rows.
    - Ensure `STYLE_IDX ROW="P"` (at link position `P`) in the parent report uses neutral unbordered styles with currency scaled by 1,000 and unsuppressed percentage styles (`NUM_DECIMALS="1"` without `REPLACE="1" REPLACE_TEXT="<empty>"`).
    - Maintain `FORMAT_SEGMENT="P - 1"` (e.g. `4` for `POSITION="5"`) to preserve KPI row calculations (such as `ANSP`).
  - **For Links at `POSITION == 0` (`DC`, `GA`, `MF`, `MK`, `RD`, `SL`)**:
    - Shift all report column formula row coordinates targeting kept rows (`>= 0`) by `+tSegs` (e.g. `PercentOfTotal([Col],[Col,1])` -> `[Col, 1 + tSegs]`: `[Col,28]` for 27-segment templates; `[Col,43]` for 42-segment templates).
    - Retain the template's own data cell styles (`<CELL_STYLE>` and `<STYLE_IDX>`) so linked rows do not inherit the suppressed Net Sales Total formatting from parent `STYLE_IDX ROW="0"`.
- **Row Border Inheritance in Parent Report**:
  - When `<STYLE_IDX>` is omitted from `.ros`, row-border styling for linked rows inherits from the parent report's `STYLE_IDX` at `ROW="P"` (where `P` is the link position/offset in `STYLE_IDX`).
  - If the parent report has a bottom border at that position (e.g. `BORDER_BOTTOM="1"` on a separator row above Gross Profit), all linked rows will inherit that underline.
  - To prevent unwanted underlines under linked rows, update the parent report's `STYLE_IDX` at that row index to use unbordered cell styles (e.g. `COL_IDX="16,19,44,19,44,44..."`).
- **`<STYLE_IDX>` Column Count (when present)**:
  - If a `.ros` Row Template does define `<STYLE_IDX>`, it only stores 1 style index per row-heading column plus **1** data cell style index (`COL_IDX="0,1"` for 1 heading layer, `COL_IDX="0,1,2"` for 2 heading layers); additional column indices are ignored by Financial Reporting.

**All three golden pairs and the 180-page runtime book comparison pass with 100% exact match.**
