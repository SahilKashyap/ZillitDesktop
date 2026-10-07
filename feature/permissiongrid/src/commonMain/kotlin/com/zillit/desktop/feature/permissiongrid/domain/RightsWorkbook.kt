package com.zillit.desktop.feature.permissiongrid.domain

/**
 * One sheet of text as an `.xlsx` — the Default Grid's "Download Excel"
 * (web `Defaultgrid.jsx` `downloadExcel`, which hands `XLSX.writeFile` the
 * same header row and `✔`/`x` cells).
 */
expect fun rightsWorkbook(sheetName: String, header: List<String>, rows: List<List<String>>): ByteArray
