package com.jupiter.shortlink.admin.remote.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

import java.util.Date;

/** 短链接分页返回参数 */
@Data
public class ShortLinkPageRespDTO {
    private Long linkId;
    private Long routeVersion;
    private Long targetRevision;
    private Long ownershipVersion;
    private Integer enableStatus;
    private String title;
    private String metadataStatus;

    /** id */
    private Long id;

    /** 域名 */
    private String domain;

    /** 短链接 */
    private String shortUri;

    /** 完整短链接 */
    private String fullShortUrl;

    /** 原始链接 */
    private String originUrl;

    /** 分组标识 */
    private String gid;

    /** 有效期类型 0：永久有效 1：用户自定义 */
    private int validDateType;

    /** 有效期 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date validDate;

    /** 创建时间参数 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    /** 描述 */
    private String describe;

    /** 网站标识 */
    private String favicon;

    /** 今日访问量 */
    private Long todayPv;

    /** 今日独立访问数 */
    private Long todayUv;

    /** 今日独立IP数 */
    private Long todayUip;

    /** 历史访问量 */
    private Long totalPv;

    /** 历史独立访问数 */
    private Long totalUv;

    /** 历史独立IP数 */
    private Long totalUip;
}
