package com.jupiter.shortlink.admin.controller;

import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.remote.ShortLinkActualRemoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController(value = "urlTitleControllerByAdmin")
@RequiredArgsConstructor
public class UrlTitleController {

    private final ShortLinkActualRemoteService shortLinkActualRemoteService;
    /**
     * 根据url获取对应网站的标题
     */
    @GetMapping("/api/short-link/admin/v1/tittle")
    public Result<String> getTitleByUrl(@RequestParam("url") String url){
        return shortLinkActualRemoteService.getTitleByUrl(url);
    }
}
