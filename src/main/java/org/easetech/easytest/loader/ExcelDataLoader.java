
package org.easetech.easytest.loader;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.easetech.easytest.util.ResourceLoader;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of {@link Loader} for the EXCEL(xls) based files. This Loader is responsible for reading a list of
 * xls based files and converting them into a data structure which is understandable by the EasyTest framework. It
 * expects the format of the Excel file to be like this :<br>
 * <code>
 * <B>testGetItems LibraryId itemType searchText</B>
 * <br>
 * <EMPTY CELL> 4 journal batman
 * <EMPTY CELL> 2 ebook   spiderman
 * <br>
 * where <B>testGetItems</B> represents the name of the test method for which the test data is being defined,<br>
 * <B>LibraryId itemType searchText</B> represents the test data fields for the test method, and</br>
 * <B>4 journal batman (and 2 ebook spiderman)</B> represents the actual test data to be passed to the test method.
 * Each row in the EXCEL file represents a single set of test data.<br>
 * 
 * Note the leading <EMPTY CELL> in the test data row. It denotes that this cell does not contain any value.It tells the 
 * framework that testGetItems is just a method name and does not have any value.<br>
 * 
 * An Excel cannot have a blank line in between test data whether it is for a single test or for multiple tests.
 * The framework is capable of handling multiple test data for multiple test methods in a single Excel file. 
 * Although a user can choose to define the test data in multiple files as well.
 * 
 * @author Anuj Kumar
 * 
 */
public class ExcelDataLoader implements Loader {

    /**
     * An instance of logger associated with the test framework.
     */
    protected static final Logger LOG = LoggerFactory.getLogger(ExcelDataLoader.class);

    /**
     * The data structure that will ultimately contain the data provided by the Excel sheet.
     */
    private transient Map<String, List<Map<String, Object>>> data = null;

    /**
     * Default no arg constructor
     */
    public ExcelDataLoader() {
        super();

    }

    /**
     * Construct a new ExcelDataLoader and also load the data.
     * 
     * @param excelInputStream the input stream to load the data from
     * @throws IOException if an IO Exception occurs
     */
    public ExcelDataLoader(final InputStream excelInputStream) throws IOException {
        this.data = loadFromSpreadsheet(excelInputStream);

    }

    /**
     * Method to get the data
     * 
     * @return loaded data
     */
    public Map<String, List<Map<String, Object>>> getData() {
        return data;
    }

    /**
     * Load the Data from Excel spreadsheet.It uses Apache POI classes to load the data.
     * 
     * @param excelFile the excel file input stream to load the data from
     * @return the loaded data.
     * @throws IOException if an exception occurs while loading the data
     */
    private Map<String, List<Map<String, Object>>> loadFromSpreadsheet(final InputStream excelFile) throws IOException {
        HSSFWorkbook workbook = new HSSFWorkbook(excelFile);

        data = new HashMap<String, List<Map<String, Object>>>();
        Sheet sheet = workbook.getSheetAt(0);
        int numberOfColumns = countNonEmptyColumns(sheet);

        Map<String, List<Map<String, Object>>> finalData = new HashMap<String, List<Map<String, Object>>>();

        Map<Integer, Object> tempData = new HashMap<Integer, Object>();
        List<Map<String, Object>> dataValues = null;
        for (Row row : sheet) {
            boolean isFirstColumn = true;
            boolean keyRow = false;

            Map<String, Object> actualData = new HashMap<String, Object>();
            for (int column = 0; column < numberOfColumns; column++) {

                Cell cell = row.getCell(column);
                Object cellData = objectFrom(workbook, cell);
                if (isFirstColumn && cellData != null) {
                    // Indicates that this is a new set of test data.
                    dataValues = new ArrayList<Map<String, Object>>();
                    // Indicates that this row consists of Keys
                    keyRow = true;
                    finalData.put(cellData.toString().trim(), dataValues);
                } else if (cellData == null) {
                    // dont do anything. May be can be used in future.
                } else {
                    if (keyRow) {
                        tempData.put(column, objectFrom(workbook, cell));
                    } else {
                        actualData.put(tempData.get(column).toString(), objectFrom(workbook, cell));
                    }

                }
                isFirstColumn = false;

            }
            if (!keyRow) {
                dataValues.add(actualData);
            }
        }
        return finalData;
    }

    /**
     * Count the number of columns, using the number of non-empty cells in the first row.
     * 
     * @param sheet the excel sheet that contains the data
     * @return number of non empty columns.
     */
    private int countNonEmptyColumns(final Sheet sheet) {
        Row firstRow = sheet.getRow(0);
        return firstEmptyCellPosition(firstRow);
    }

    /**
     * Get the first empty cell position in the sheet
     * 
     * @param cells the row in the sheet
     * @return the first empty cell position in the sheet
     */
    private int firstEmptyCellPosition(final Row cells) {
        int columnCount = 0;
        for (Cell cell : cells) {
            if (cell.getCellType() == Cell.CELL_TYPE_BLANK) {
                break;
            }
            columnCount++;
        }
        return columnCount;
    }

    /**
     * Get the cell value from the workbook and the specified cell within the workbook.
     * 
     * @param workbook the workbook containing the cells
     * @param cell the cell containing the data
     * @return the object representation of the data
     */
    private Object objectFrom(final HSSFWorkbook workbook, final Cell cell) {
        Object cellValue = null;

        if (cell == null || cell.getCellType() == Cell.CELL_TYPE_BLANK) {
            cellValue = null;
        } else if (cell.getCellType() == Cell.CELL_TYPE_STRING) {
            cellValue = cell.getRichStringCellValue().getString();
        } else if (cell.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            cellValue = getNumericCellValue(cell);
        } else if (cell.getCellType() == Cell.CELL_TYPE_BOOLEAN) {
            cellValue = cell.getBooleanCellValue();
        } else if (cell.getCellType() == Cell.CELL_TYPE_FORMULA) {
            cellValue = evaluateCellFormula(workbook, cell);
        }

        return cellValue;

    }

    /**
     * Get numeric cell value
     * 
     * @param cell the cell to get the data from
     * @return the object representation of numeric cell value.
     */
    private Object getNumericCellValue(final Cell cell) {
        Object cellValue;
        if (DateUtil.isCellDateFormatted(cell)) {
            cellValue = new Date(cell.getDateCellValue().getTime());
        } else {
            cellValue = cell.getNumericCellValue();
            // below is the work around to remove suffix .0 from numeric fields
            if (cellValue != null && cellValue.toString().endsWith(".0")) {
                cellValue = cellValue.toString().replace(".0", "");
            }
        }
        return cellValue;
    }

    /**
     * Evaluate if the cell contains the formula
     * 
     * @param workbook the workbook that contains the cell
     * @param cell the cell that contains the formula
     * @return the object representation of formula cell value.
     */
    private Object evaluateCellFormula(final HSSFWorkbook workbook, final Cell cell) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        CellValue cellValue = evaluator.evaluate(cell);
        Object result = null;

        if (cellValue.getCellType() == Cell.CELL_TYPE_BOOLEAN) {
            result = cellValue.getBooleanValue();
        } else if (cellValue.getCellType() == Cell.CELL_TYPE_NUMERIC) {
            result = cellValue.getNumberValue();
        } else if (cellValue.getCellType() == Cell.CELL_TYPE_STRING) {
            result = cellValue.getStringValue();
        }

        return result;
    }

    /**
     * Construct a new CSVDataLoader and also load the data.
     * 
     * @param dataFiles the list of input stream string files to load the data from
     * @return a Map of method name and the list of associated test data with that method name
     * @throws IOException if an IO Exception occurs
     */
    private Map<String, List<Map<String, Object>>> loadExcelData(final List<String> dataFiles) throws IOException {
        LOG.debug("loadExcelData started", dataFiles);
        Map<String, List<Map<String, Object>>> data = null;
        Map<String, List<Map<String, Object>>> finalData = new HashMap<String, List<Map<String, Object>>>();
        for (String filePath : dataFiles) {
            try {
                ResourceLoader resource = new ResourceLoader(filePath);
                data = loadFromSpreadsheet(resource.getInputStream());
            } catch (FileNotFoundException e) {
                LOG.error("The specified file was not found. The path is : {}", filePath);
                LOG.error("Continuing with the loading of next file.");
                continue;
            } catch (IOException e) {
                LOG.error("IO Exception occured while trying to read the data from the file : {}", filePath);
                LOG.error("Continuing with the loading of next file.");
                continue;
            }
            finalData.putAll(data);
        }
        LOG.debug("loadExcelData finisihed", finalData);
        return finalData;

    }

    /**
     * Main entry point for the Loader
     */
    @Override
    public Map<String, List<Map<String, Object>>> loadData(String[] filePaths) {
        LOG.info("loadData started" + filePaths);
        Map<String, List<Map<String, Object>>> result = new HashMap<String, List<Map<String, Object>>>();
        try {
            result = loadExcelData(Arrays.asList(filePaths));
        } catch (IOException e) {
            Assert.fail("An I/O exception occured while reading the files from the path :" + filePaths.toString());
        }
        LOG.debug("loadData finished" + result);
        LOG.info("loadData finished");
        return result;
    }

    /**
     * Write the data back to the Excel file. The data is written to the same Excel File as it was read from.
     * 
     * @param filePath the path of the file specifying the the file to which data needs to be written.
     * @param map an instance of {@link Map} containing the data that needs to be written to the file.
     */
    @Override
    public void writeData(String filePath, Map<String, List<Map<String, Object>>> map) {
        LOG.debug("writeData started, filePath:" + filePath + ", data map size:" + map.size() + ", data map:" + map);
        try {

            writeExcelData(filePath, map);
        } catch (IOException e) {
            Assert.fail("An I/O exception occured while reading the files from the path :" + filePath.toString());
        }
        LOG.info("writeData finished");
    }

    /**
     * writes map data to excel file. it gets FileWriter from ResourceLoader and writeDataToSpreadsheet
     * 
     * @param filePath The path to the file to which the data will be written
     * @param data a Map of method name and the list of associated test input and output data with that method name
     * @throws IOException if an IO Exception occurs
     */
    private void writeExcelData(String filePath, Map<String, List<Map<String, Object>>> data) throws IOException {
        LOG.debug("writeExcelData started" + filePath + data.size());
        try {
            ResourceLoader resource = new ResourceLoader(filePath);
            writeDataToSpreadsheet(resource, data);
        } catch (FileNotFoundException e) {
            LOG.error("The specified file was not found. The path is : {}", filePath);
            LOG.error("Continuing with the loading of next file.");
        } catch (IOException e) {
            LOG.error("IO Exception occured while trying to read the data from the file : {}", filePath);
            LOG.error("Continuing with the loading of next file.");
        }

        LOG.debug("writeExcelData finished" + filePath + data.size());
    }

    private void writeDataToSpreadsheet(ResourceLoader resource, Map<String, List<Map<String, Object>>> data)
        throws IOException {
        LOG.debug("writeDataToSpreadsheet started" + resource.toString() + data);
        Workbook workbook;
        try {

            workbook = WorkbookFactory.create(new POIFSFileSystem(resource.getInputStream()));

        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }

        Sheet sheet = workbook.getSheetAt(0);
        // Sheet sheet = workbook.createSheet();

        for (String methodName : data.keySet()) {
            int rowNum = 0;

            boolean isActualResultHeaderWritten = false;
            for (Map<String, Object> methodData : data.get(methodName)) {
                // rowNum increment by one to proceed with next record of the method.
                rowNum++;

                Object actualResult = methodData.get(ACTUAL_RESULT);
                if (actualResult != null) {
                    // getting no.of columns in the record
                    int columnNum = methodData.size();
                    if (!isActualResultHeaderWritten) {
                        Integer recordNum = getMethodRowNumFromExcel(sheet, methodName);
                        if (recordNum != null) {
                            // Write the actual result and test status headers.
                            writeDataToCell(sheet, recordNum, columnNum, ACTUAL_RESULT);
                            //writeDataToCell(sheet, recordNum, columnNum + 1, TEST_STATUS);
                            rowNum = rowNum + recordNum;
                            isActualResultHeaderWritten = true;
                        }
                    }
                    LOG.info("rowNum:" + rowNum);

                    // Write the actual result and test status values.
                    if (isActualResultHeaderWritten) {
                        LOG.debug("actualResult:" + actualResult.toString());
                        writeDataToCell(sheet, rowNum, columnNum, actualResult.toString());
                        Object testStatus = methodData.get(TEST_STATUS);
                        if (testStatus != null) {
                            LOG.debug("testStatus:" + testStatus.toString());
                            writeDataToCell(sheet, rowNum, columnNum + 1, testStatus.toString());
                        }
                    }

                }
            }
        }
        // Write the output to a file
        workbook.write(resource.getFileOutputStream());
        LOG.debug("writeDataToSpreadsheet finished");

    }

    private Integer getMethodRowNumFromExcel(Sheet sheet, String methodName) {
        Integer rowNum = null;
        for (Row row : sheet) {
            // getting first cell value as method name is available in first column
            Cell cell = row.getCell(0);
            if (cell != null) {
                String cellData = cell.getStringCellValue();
                if (cellData != null && methodName.equals(cellData.trim())) {
                    rowNum = cell.getRow().getRowNum();
                    System.out.println("methodName matched at rowNum:" + rowNum);
                    break;
                }
            }
        }
        LOG.debug("getMethodRowNumFromExcel finished:" + methodName + rowNum);
        return rowNum;
    }

    private void writeDataToCell(Sheet sheet, int rowNum, int columnNum, String value) {
        Row row = sheet.getRow(rowNum);
        Cell cell = row.getCell(columnNum);
        if (cell == null) {
            cell = row.createCell(columnNum);
        }
        cell.setCellType(Cell.CELL_TYPE_STRING);
        cell.setCellValue(value);
    }
}