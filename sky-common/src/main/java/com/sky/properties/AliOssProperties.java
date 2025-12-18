package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.alioss")
@Data
public class AliOssProperties {
    //会自动把yml配置文件中的配置项封装成一个对象
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;

}
