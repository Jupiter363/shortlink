package com.nageoffer.shortlink.project.service;

/**
 * url标题接口层
 */
public interface UrlTitleService {
    /**
     * 根据url获取标题
     * @param url 目标网站地址
     * @return 目标网站标题
     */
    String getTitleByUrl(String url);
}
