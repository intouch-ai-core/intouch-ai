package com.blueisle.intouch.tools.excel

import com.blueisle.intouch.tool.IToolConnector
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import java.io.*

/**
 * InTouch Tool: Excel
 *
 * Read and write Microsoft Excel files (.xlsx and .xls):
 * - Read: extract sheet data to CSV or JSON, with row/column range selection
 * - Write: create Excel files from CSV data with formatting
 * - List: enumerate sheets in a workbook
 * - Convert: CSV to Excel or Excel to CSV
 *
 * Uses Apache POI — supports both .xlsx (XSSF) and .xls (HSSF) formats.
 */
class ExcelTool : IToolConnector {

    private val mapper = ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private var debug = false
    override var messageCallback: ((String) -> Unit)? = null

    companion object {
        const val SUCCESS = 0
        const val WARNING = 1
        const val FAILED = 2
    }

    override fun getName() = "excel"
    override fun getDisplayName() = "Excel"
    override fun getProvider() = "Blue Isle"
    override fun getVersion() = "1.0.0"
    override fun getPublishedOutputs(): List<String> = listOf("columnCount", "operation", "outputFile", "rowCount", "sheetCount", "sheets")
    override fun getModelNames() = listOf("read", "write", "list-sheets", "csv-to-excel", "excel-to-csv")

    override fun getModel(name: String): String = """
        {
            "schema": {
                "type": "object",
                "properties": {
                    "operation": { "type": "string", "enum": ["read", "write", "list-sheets", "csv-to-excel", "excel-to-csv"] },
                    "inputFile": { "type": "string", "format": "filepath", "description": "Input Excel or CSV file path" },
                    "outputFile": { "type": "string", "format": "filepath", "description": "Output file path" },
                    "sheetName": { "type": "string", "description": "Sheet name (default: first sheet)", "default": "" },
                    "sheetIndex": { "type": "integer", "description": "Sheet index (0-based, used if sheetName is blank)", "default": 0 },
                    "hasHeader": { "type": "boolean", "description": "First row is a header", "default": true },
                    "delimiter": { "type": "string", "description": "CSV delimiter for read/write", "default": "," },
                    "startRow": { "type": "integer", "description": "Start row (0-based)", "default": 0 },
                    "maxRows": { "type": "integer", "description": "Maximum rows to read (0 = all)", "default": 0 }
                },
                "required": ["operation", "inputFile"]
            },
            "uischema": {
                "type": "VerticalLayout",
                "elements": [
                    { "type": "Control", "scope": "#/properties/operation" },
                    { "type": "Control", "scope": "#/properties/inputFile" },
                    { "type": "Control", "scope": "#/properties/outputFile" },
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/sheetName" },
                        { "type": "Control", "scope": "#/properties/sheetIndex" }
                    ]},
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/hasHeader" },
                        { "type": "Control", "scope": "#/properties/delimiter" }
                    ]},
                    { "type": "HorizontalLayout", "elements": [
                        { "type": "Control", "scope": "#/properties/startRow" },
                        { "type": "Control", "scope": "#/properties/maxRows" }
                    ]}
                ]
            }
        }
    """.trimIndent()

    override fun getDefaultJson(name: String): String = mapper.writeValueAsString(mapOf(
        "operation" to "read", "inputFile" to "", "outputFile" to "",
        "sheetName" to "", "sheetIndex" to 0, "hasHeader" to true,
        "delimiter" to ",", "startRow" to 0, "maxRows" to 0
    ))

    override fun isCredentialBased() = false
    override fun getCredentialModel() = "{}"
    override fun getDefaultCredentialJson() = ""
    override fun testCredential(json: String) = ""
    override fun setCredentialProperties(json: String) {}

    override fun execute(taskJson: String): Triple<Int, String, String> {
        val config = mapper.readValue(taskJson, Map::class.java)
        val operation = config["operation"]?.toString()?.lowercase() ?: "read"
        val inputFile = config["inputFile"]?.toString()?.trim() ?: ""
        val outputFile = config["outputFile"]?.toString()?.trim() ?: ""
        val sheetName = config["sheetName"]?.toString()?.trim() ?: ""
        val sheetIndex = (config["sheetIndex"] as? Number)?.toInt() ?: 0
        val hasHeader = config["hasHeader"] as? Boolean ?: true
        val delimiter = config["delimiter"]?.toString() ?: ","
        val startRow = (config["startRow"] as? Number)?.toInt() ?: 0
        val maxRows = (config["maxRows"] as? Number)?.toInt() ?: 0

        if (inputFile.isBlank()) return Triple(FAILED, "Input file is required", "{}")

        val published = mutableMapOf<String, String>()

        try {
            when (operation) {
                "read", "excel-to-csv" -> {
                    if (outputFile.isBlank()) return Triple(FAILED, "Output file required for $operation", "{}")
                    val (rows, cols) = readExcelToCsv(inputFile, outputFile, sheetName, sheetIndex, hasHeader, delimiter, startRow, maxRows)
                    published["operation"] = operation
                    published["rowCount"] = rows.toString()
                    published["columnCount"] = cols.toString()
                    published["outputFile"] = outputFile
                    postMessage("Exported $rows rows ($cols columns) to $outputFile")
                }
                "write", "csv-to-excel" -> {
                    if (outputFile.isBlank()) return Triple(FAILED, "Output file required for $operation", "{}")
                    val (rows, cols) = writeCsvToExcel(inputFile, outputFile, sheetName.ifBlank { "Sheet1" }, hasHeader, delimiter)
                    published["operation"] = operation
                    published["rowCount"] = rows.toString()
                    published["columnCount"] = cols.toString()
                    published["outputFile"] = outputFile
                    postMessage("Created Excel with $rows rows ($cols columns) at $outputFile")
                }
                "list-sheets" -> {
                    val sheets = listSheets(inputFile)
                    published["operation"] = "list-sheets"
                    published["sheetCount"] = sheets.size.toString()
                    published["sheets"] = sheets.joinToString(",")
                    postMessage("Found ${sheets.size} sheet(s): ${sheets.joinToString(", ")}")
                }
                else -> return Triple(FAILED, "Unknown operation: $operation", "{}")
            }
            return Triple(SUCCESS, "$operation completed", mapper.writeValueAsString(published))
        } catch (ex: Exception) {
            return Triple(FAILED, "$operation failed: ${ex.message}", mapper.writeValueAsString(published))
        }
    }

    private fun readExcelToCsv(inputFile: String, outputFile: String, sheetName: String,
                                sheetIndex: Int, hasHeader: Boolean, delimiter: String,
                                startRow: Int, maxRows: Int): Pair<Int, Int> {
        val workbook = openWorkbook(inputFile)
        val sheet = if (sheetName.isNotBlank()) workbook.getSheet(sheetName) ?: throw Exception("Sheet '$sheetName' not found")
        else workbook.getSheetAt(sheetIndex)

        val formatter = DataFormatter()
        File(outputFile).parentFile?.mkdirs()
        var rowCount = 0
        var colCount = 0

        BufferedWriter(FileWriter(outputFile)).use { writer ->
            for (i in startRow..sheet.lastRowNum) {
                if (maxRows > 0 && rowCount >= maxRows) break
                val row = sheet.getRow(i) ?: continue
                if (row.lastCellNum > colCount) colCount = row.lastCellNum.toInt()
                val values = (0 until row.lastCellNum).map { c ->
                    val cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                    formatter.formatCellValue(cell)
                }
                writer.write(values.joinToString(delimiter))
                writer.newLine()
                rowCount++
            }
        }
        workbook.close()
        return rowCount to colCount
    }

    private fun writeCsvToExcel(inputFile: String, outputFile: String, sheetName: String,
                                 hasHeader: Boolean, delimiter: String): Pair<Int, Int> {
        val workbook = if (outputFile.endsWith(".xls")) HSSFWorkbook() else XSSFWorkbook()
        val sheet = workbook.createSheet(sheetName)
        val lines = File(inputFile).readLines()
        var colCount = 0

        for ((idx, line) in lines.withIndex()) {
            val values = line.split(delimiter)
            if (values.size > colCount) colCount = values.size
            val row = sheet.createRow(idx)
            values.forEachIndexed { col, value ->
                val cell = row.createCell(col)
                val num = value.trim().toDoubleOrNull()
                if (num != null) cell.setCellValue(num) else cell.setCellValue(value)
            }
        }

        // Auto-size columns
        for (i in 0 until colCount) { try { sheet.autoSizeColumn(i) } catch (_: Exception) {} }

        File(outputFile).parentFile?.mkdirs()
        FileOutputStream(outputFile).use { workbook.write(it) }
        workbook.close()
        return lines.size to colCount
    }

    private fun listSheets(inputFile: String): List<String> {
        val workbook = openWorkbook(inputFile)
        val names = (0 until workbook.numberOfSheets).map { workbook.getSheetName(it) }
        workbook.close()
        return names
    }

    private fun openWorkbook(path: String): Workbook {
        val file = File(path)
        if (!file.exists()) throw Exception("File not found: $path")
        return WorkbookFactory.create(FileInputStream(file))
    }

    override fun kill() {}
    override fun isKillable() = false
    override fun setDebug(enabled: Boolean) { debug = enabled }
    private fun postMessage(msg: String) { messageCallback?.invoke(msg) }
}
