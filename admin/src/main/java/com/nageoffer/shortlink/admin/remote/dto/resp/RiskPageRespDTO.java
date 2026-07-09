package com.nageoffer.shortlink.admin.remote.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class RiskPageRespDTO<T> {

    private List<T> records;

    private long total;

    private int pageNo;

    private int pageSize;
}
