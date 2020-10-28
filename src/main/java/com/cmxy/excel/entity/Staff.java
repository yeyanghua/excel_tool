package com.cmxy.excel.entity;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class Staff {

    private String name;

    private String idCard;

    private String phone;

    private String bankNo;

    private Integer column;
}
