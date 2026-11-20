package com.dinemaster.config;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class RedisConfig {

    @Bean
    LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
                properties.getHost(), properties.getPort());
        if (StringUtils.hasText(properties.getPassword())) {
            server.setPassword(RedisPassword.of(properties.getPassword()));
        }

        LettuceClientConfiguration client = properties.getSsl().isEnabled()
                ? LettuceClientConfiguration.builder().useSsl().build()
                : LettuceClientConfiguration.builder().build();
        return new LettuceConnectionFactory(server, client);
    }
}
