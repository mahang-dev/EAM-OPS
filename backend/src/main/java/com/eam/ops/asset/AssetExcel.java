package com.eam.ops.asset;

import com.eam.ops.common.*;
import com.eam.ops.security.Access;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetExcel {
  private static final String[] COLUMNS = {
    "asset_code",
    "name",
    "category_id",
    "department_id",
    "location_id",
    "owner_id",
    "model",
    "ip_address"
  };
  private final AssetService assets;
  private final Access access;
  private final Db db;

  public AssetExcel(AssetService assets, Access access, Db db) {
    this.assets = assets;
    this.access = access;
    this.db = db;
  }

  public byte[] export(boolean template) throws IOException {
    access.need("asset:read");
    try (var book = new XSSFWorkbook();
        var out = new ByteArrayOutputStream()) {
      var sheet = book.createSheet("资产台账");
      var header = sheet.createRow(0);
      for (int c = 0; c < COLUMNS.length; c++) {
        header.createCell(c).setCellValue(COLUMNS[c]);
        sheet.setColumnWidth(c, 24 * 256);
      }
      if (!template) {
        var rows =
            db.list(
                "SELECT * FROM ops_asset WHERE " + access.filter("") + " ORDER BY id LIMIT 10001");
        BusinessException.require(rows.size() <= 10000, 400, "单次导出限 10000 条");
        int n = 1;
        for (var row : rows) {
          var x = sheet.createRow(n++);
          for (int c = 0; c < COLUMNS.length; c++)
            x.createCell(c).setCellValue(String.valueOf(row.get(COLUMNS[c])));
        }
      }
      sheet.createFreezePane(0, 1);
      book.write(out);
      return out.toByteArray();
    }
  }

  @Transactional
  public Object importFile(MultipartFile file, boolean commit) throws IOException {
    access.need("asset:write");
    var errors = new ArrayList<Map<String, Object>>();
    var rows = new ArrayList<Map<String, Object>>();
    var codes = new HashSet<String>();
    try (var book = new XSSFWorkbook(file.getInputStream())) {
      BusinessException.require(book.getNumberOfSheets() > 0, 400, "文件没有工作表");
      var sheet = book.getSheetAt(0);
      var formatter = new DataFormatter();
      BusinessException.require(
          sheet.getLastRowNum() >= 1 && sheet.getLastRowNum() <= 1000, 400, "每次导入 1–1000 行");
      for (int c = 0; c < COLUMNS.length; c++)
        BusinessException.require(
            sheet.getRow(0) != null
                && COLUMNS[c].equals(formatter.formatCellValue(sheet.getRow(0).getCell(c))),
            400,
            "请使用下载的导入模板，表头不匹配");
      for (int n = 1; n <= sheet.getLastRowNum(); n++) {
        var row = new LinkedHashMap<String, Object>();
        var excel = sheet.getRow(n);
        if (excel == null) continue;
        for (int c = 0; c < COLUMNS.length; c++)
          row.put(COLUMNS[c], formatter.formatCellValue(excel.getCell(c)).trim());
        try {
          assets.validate(row);
          String code = row.get("asset_code").toString();
          BusinessException.require(
              codes.add(code)
                  && db.count("SELECT COUNT(*) FROM ops_asset WHERE asset_code=?", code) == 0,
              409,
              "资产编号重复");
          rows.add(row);
        } catch (BusinessException e) {
          errors.add(Map.of("row", n + 1, "message", e.getMessage()));
        }
      }
    }
    if (!errors.isEmpty())
      return Map.of("valid", false, "errors", errors, "count", rows.size(), "committed", false);
    if (commit) for (var row : rows) assets.create(row);
    return Map.of("valid", true, "errors", errors, "count", rows.size(), "committed", commit);
  }
}
