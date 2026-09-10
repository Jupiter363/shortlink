package com.jupiter.shortlink.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkBatchCreateReqDTO;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkCreateReqDTO;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkPageReqDTO;
import com.jupiter.shortlink.admin.remote.dto.req.ShortLinkUpdateReqDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkBatchCreateRespDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkCreateRespDTO;
import com.jupiter.shortlink.admin.remote.dto.resp.ShortLinkPageRespDTO;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 短链接后管控制层 */
@RestController(value = "shortLinkControllerByAdmin")
@RequiredArgsConstructor
public class ShortLinkController {
    private final ShortLinkActualRemoteService shortLinkActualRemoteService;
    private final com.jupiter.shortlink.admin.remote.analytics.LinkPageAnalyticsService pages;

    /** 创建短链接 */
    @PostMapping("/api/short-link/admin/v1/create")
    public Result<ShortLinkCreateRespDTO> createShortLink(
            @RequestBody ShortLinkCreateReqDTO requestParam) {
        return shortLinkActualRemoteService.createShortLink(requestParam);
    }

    /** 修改短链接 */
    @PostMapping("/api/short-link/admin/v1/update")
    public Result<Void> updateShortLink(@RequestBody ShortLinkUpdateReqDTO requestParam) {
        shortLinkActualRemoteService.updateShortLink(requestParam);
        return Results.success();
    }

    /** 分页查询短链接 */
    @GetMapping("/api/short-link/admin/v1/page")
    public Result<Page<ShortLinkPageRespDTO>> pageShortLink(ShortLinkPageReqDTO requestParam) {
        return pages.page(requestParam);
    }

    /** 批量创建短链接 */
    @SneakyThrows
    @PostMapping("/api/short-link/admin/v1/create/batch")
    public org.springframework.http.ResponseEntity<Result<ShortLinkBatchCreateRespDTO>>
            batchCreateShortLink(@RequestBody ShortLinkBatchCreateReqDTO requestParam) {
        Result<ShortLinkBatchCreateRespDTO> result =
                shortLinkActualRemoteService.batchCreateShortLink(requestParam);
        if (result == null || !result.isSuccess() || result.getData() == null)
            throw new IllegalStateException("Batch command unavailable");
        if (result.getData().getJobId() != null)
            result.getData()
                    .setResultUrl(
                            "/api/short-link/admin/v1/batches/"
                                    + result.getData().getJobId()
                                    + "/rows");
        return org.springframework.http.ResponseEntity.status(
                        result.getData().getJobId() == null ? 200 : 202)
                .body(result);
    }
}
