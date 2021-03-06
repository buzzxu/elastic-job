/**
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.reg.zookeeper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.CuratorFrameworkFactory.Builder;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.TreeCache;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;

import com.dangdang.ddframe.reg.base.CoordinatorRegistryCenter;
import com.dangdang.ddframe.reg.exception.LocalPropertiesFileNotFoundException;
import com.dangdang.ddframe.reg.exception.RegExceptionHandler;
import com.google.common.base.Strings;

/**
 * 基于Zookeeper的注册中心.
 * 
 * @author zhangliang
 */
@Slf4j
public class ZookeeperRegistryCenter implements CoordinatorRegistryCenter {
    
    @Getter(AccessLevel.PROTECTED)
    private ZookeeperConfiguration zkConfig;
    
    private CuratorFramework client;
    
    private TreeCache cache;
    
    public ZookeeperRegistryCenter(final ZookeeperConfiguration zookeeperConfiguration) {
        zkConfig = zookeeperConfiguration;
    }
    
    public void init() {
        log.debug("Elastic job: zookeeper registry center init, server lists is: {}.", zkConfig.getServerLists());
        Builder builder = CuratorFrameworkFactory.builder()
                .connectString(zkConfig.getServerLists())
                .retryPolicy(new ExponentialBackoffRetry(zkConfig.getBaseSleepTimeMilliseconds(), zkConfig.getMaxRetries(), zkConfig.getMaxSleepTimeMilliseconds()))
                .namespace(zkConfig.getNamespace());
        if (0 != zkConfig.getSessionTimeoutMilliseconds()) {
            builder.sessionTimeoutMs(zkConfig.getSessionTimeoutMilliseconds());
        }
        if (0 == zkConfig.getConnectionTimeoutMilliseconds()) {
            builder.connectionTimeoutMs(zkConfig.getConnectionTimeoutMilliseconds());
        }
        if (!Strings.isNullOrEmpty(zkConfig.getDigest())) {
            builder.authorization("digest", zkConfig.getDigest().getBytes(Charset.forName("UTF-8")))
                   .aclProvider(new ACLProvider() {
                       
                       @Override
                       public List<ACL> getDefaultAcl() {
                           return ZooDefs.Ids.CREATOR_ALL_ACL;
                       }
                       
                       @Override
                       public List<ACL> getAclForPath(final String path) {
                           return ZooDefs.Ids.CREATOR_ALL_ACL;
                       }
                   });
        }
        client = builder.build();
        client.start();
        try {
            client.blockUntilConnected();
            if (!Strings.isNullOrEmpty(zkConfig.getLocalPropertiesPath())) {
                fillData();
            }
            cacheData();
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
        }
    }
    
    private void fillData() throws Exception {
        for (Entry<Object, Object> entry : loadLocalProperties().entrySet()) {
            String key = entry.getKey().toString();
            byte[] value = entry.getValue().toString().getBytes(Charset.forName("UTF-8"));
            if (null == client.checkExists().forPath(key)) {
                client.create().creatingParentsIfNeeded().forPath(key, value);
            } else if (zkConfig.isOverwrite() || 0 == client.getData().forPath(key).length) {
                client.setData().forPath(key, value);
            }
        }
    }
    
    private Properties loadLocalProperties() {
        Properties result = new Properties();
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(zkConfig.getLocalPropertiesPath())) {
            if (null == input) {
                throw new LocalPropertiesFileNotFoundException(zkConfig.getLocalPropertiesPath());
            }
            result.load(input);
        } catch (final IOException ex) {
            throw new LocalPropertiesFileNotFoundException(ex);
        }
        return result;
    }
    
    private void cacheData() throws Exception {
        cache = new TreeCache(client, "/");
        cache.start();
    }
    
    @Override
    public void close() {
        if (null != cache) {
            cache.close();
        }
        waitForCacheClose();
        CloseableUtils.closeQuietly(client);
    }
    
    /* TODO 等待500ms, cache先关闭再关闭client, 否则会抛异常
     * 因为异步处理, 可能会导致client先关闭而cache还未关闭结束.
     * 等待Curator新版本解决这个bug.
     * BUG地址：https://issues.apache.org/jira/browse/CURATOR-157
     */
    private void waitForCacheClose() {
        try {
            Thread.sleep(500L);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    public String get(final String key) {
        if (null == cache) {
            return null;
        }
        ChildData resultIncache = cache.getCurrentData(key);
        if (null != resultIncache) {
            return null == resultIncache.getData() ? null : new String(resultIncache.getData(), Charset.forName("UTF-8"));
        }
        return getDirectly(key);
    }
    
    @Override
    public String getDirectly(final String key) {
        try {
            return new String(client.getData().forPath(key), Charset.forName("UTF-8"));
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
            return null;
        }
    }
    
    public List<String> getChildrenKeys(final String key) {
        try {
            return client.getChildren().forPath(key);
         //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
            return Collections.emptyList();
        }
    }
    
    @Override
    public boolean isExisted(final String key) {
        try {
            return null != client.checkExists().forPath(key);
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
            return false;
        }
    }
    
    @Override
    public void persist(final String key, final String value) {
        try {
            if (!isExisted(key)) {
                client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(key, value.getBytes());
            } else {
                update(key, value);
            }
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
        }
    }
    
    @Override
    public void update(final String key, final String value) {
        try {
            client.inTransaction().check().forPath(key).and().setData().forPath(key, value.getBytes(Charset.forName("UTF-8"))).and().commit();
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
        }
    }
    
    @Override
    public void persistEphemeral(final String key, final String value) {
        try {
            if (isExisted(key)) {
                client.delete().deletingChildrenIfNeeded().forPath(key);
            }
            client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(key, value.getBytes(Charset.forName("UTF-8")));
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
        }
    }
    
    @Override
    public void persistEphemeralSequential(final String key) {
        try {
            client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(key);
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
        }
    }
    
    @Override
    public void remove(final String key) {
        try {
            client.delete().deletingChildrenIfNeeded().forPath(key);
        //CHECKSTYLE:OFF
        } catch (final Exception ex) {
        //CHECKSTYLE:ON
            RegExceptionHandler.handleException(ex);
        }
    }
    
    @Override
    public Object getRawClient() {
        return client;
    }
    
    @Override
    public Object getRawCache() {
        return cache;
    }
}
