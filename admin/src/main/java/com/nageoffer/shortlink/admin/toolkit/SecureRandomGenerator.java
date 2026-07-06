package com.nageoffer.shortlink.admin.toolkit;

import java.security.SecureRandom;

public class SecureRandomGenerator {
    private static final String CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成随机分组id
     * @return 6位随机数包含数字和字母
     */
    public static String generateSecureRandomString() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            int index = secureRandom.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }
        return sb.toString();
    }
}