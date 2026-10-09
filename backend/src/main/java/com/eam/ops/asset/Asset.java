package com.eam.ops.asset;

import com.baomidou.mybatisplus.annotation.*;

@TableName("ops_asset")
public class Asset {
  @TableId(type = IdType.AUTO)
  public Long id;

  public String assetCode, name, model, ipAddress, status;
  public Long categoryId, departmentId, locationId, ownerId, createdBy;
  public Integer version;
  public java.time.LocalDateTime createdAt, updatedAt;
}
