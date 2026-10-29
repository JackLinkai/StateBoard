package io.asc.storage;

import io.asc.domain.StateItem;
import io.asc.storage.converter.StateItemConverter;
import io.asc.storage.entity.StateItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis 热状态缓存：按 (taskId, version) 缓存任务的状态项列表，TTL 用
 * {@code asc.compiler.soft-context-ttl}（默认 30m）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>缓存的是<b>原始状态项</b>而不是编译后的卡——编译是纯函数、很快，
 *       真正贵的是 DB 查询；每次请求重新编译，事实的 TTL 过期判定永远精确，
 *       缓存命中也不会把已过期的事实端给 Agent；</li>
 *   <li>key 带任务版本号：任务一有写入版本就变，旧 key 等 TTL 自然过期，
 *       天然规避了"任务更新后缓存失效"的主动清理；</li>
 *   <li>Redis 挂了不拖垮主流程：读写异常一律当缓存未命中处理（log.warn 降级）。
 *       热缓存是加速器，不是正确性依赖。</li>
 * </ul>
 *
 * <p>序列化复用 storage 层的 StateItemConverter：状态项先转成
 * type + payload 的 entity 形态再进 JSON，domain record 上仍然零 Jackson 注解。
 */
@Component
public class HotStateCache {

    private static final Logger log = LoggerFactory.getLogger(HotStateCache.class);
    private static final String KEY_PREFIX = "asc:items:";

    private final StringRedisTemplate redis;
    private final StateItemConverter converter;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public HotStateCache(StringRedisTemplate redis,
                         StateItemConverter converter,
                         ObjectMapper objectMapper,
                         @Value("${asc.compiler.soft-context-ttl:30m}") Duration ttl) {
        this.redis = redis;
        this.converter = converter;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    /**
     * 取缓存的状态项。key 带版本号：version 对不上就是 miss，等 TTL 过期。
     */
    public Optional<List<StateItem>> get(String taskId, long version) {
        try {
            String json = redis.opsForValue().get(key(taskId, version));
            if (json == null) {
                return Optional.empty();
            }
            List<StateItemEntity> entities =
                    objectMapper.readValue(json, new TypeReference<List<StateItemEntity>>() {});
            return Optional.of(entities.stream().map(converter::toDomain).toList());
        } catch (Exception e) {
            log.warn("hot state cache read failed for {}:{}, falling back to DB", taskId, version, e);
            return Optional.empty();
        }
    }

    /** 写入缓存，带 TTL。失败只记日志，不影响主流程。 */
    public void put(String taskId, long version, List<StateItem> items) {
        try {
            List<StateItemEntity> entities = items.stream().map(converter::toEntity).toList();
            redis.opsForValue().set(key(taskId, version),
                    objectMapper.writeValueAsString(entities), ttl);
        } catch (Exception e) {
            log.warn("hot state cache write failed for {}:{}", taskId, version, e);
        }
    }

    private String key(String taskId, long version) {
        return KEY_PREFIX + taskId + ":v" + version;
    }
}
