package com.google.devtools.build.lib.remote;

import com.hazelcast.cache.HazelcastCachingProvider;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientNetworkConfig;

import java.net.URI;
import java.util.Properties;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.AccessedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.spi.CachingProvider;

/**
 * Hazelcast implementation of the action cache.
 */
public class HazelcastCacheFactory {

  private static final String CACHE_NAME = "hazelcast-build-cache";
    
  public HazelcastCacheFactory() {
  }

  public Cache<String, byte[]> create(RemoteOptions options) {
    final CachingProvider cachingProvider = Caching.getCachingProvider(options.memcacheProvider);

    Properties prop = new Properties();
    if (options.hazelcastConfiguration != null) {
        // Hazelcast client is very weird that the URI passed to caching provider is not the actual
        // configuration file. The location to the configuration file is passed through properties.
        // The same for the instance name.
        prop.setProperty(HazelcastCachingProvider.HAZELCAST_CONFIG_LOCATION,
                         options.hazelcastConfiguration);
    }
    if (options.hazelcastNode != null) {
        ClientConfig config = new ClientConfig();
        ClientNetworkConfig net = config.getNetworkConfig();
        net.addAddress(options.hazelcastNode.split(","));
        HazelcastInstance instance = HazelcastClient.newHazelcastClient(config);
        prop.setProperty(HazelcastCachingProvider.HAZELCAST_INSTANCE_NAME,
                         instance.getName());
    }
    
    final CacheManager cacheManager = cachingProvider.getCacheManager(
        options.memcacheUri == null ? null : URI.create(options.memcacheUri), null, prop);
    Cache<String, byte[]> cache = cacheManager.getCache(CACHE_NAME, String.class, byte[].class);
    if (cache != null)
      return cache;

    MutableConfiguration<String, byte[]> config = new MutableConfiguration<String, byte[]>();
    config.setStoreByValue(true)
            .setTypes(String.class, byte[].class)
            .setExpiryPolicyFactory(AccessedExpiryPolicy.factoryOf(Duration.ONE_HOUR))
            .setStatisticsEnabled(false);
    return cacheManager.createCache(CACHE_NAME, config);
  }
}
