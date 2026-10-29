package io.asc.storage;

import io.asc.domain.StateCard;
import io.asc.storage.entity.StateCardVersionEntity;
import io.asc.storage.repository.StateCardVersionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 状态卡版本存取门面：application 层访问 state_card_version 表的唯一入口。
 *
 * <p>每次编译产出的<b>全量卡</b>（未按预算裁剪）在这里持久化为正式版本，
 * 回放和审计按 (taskId, version) 取到的就是这张卡；预算裁剪是注入前的派生视图，
 * 不落库。同 (taskId, version) 重复编译会覆盖旧 JSON（JPA merge 语义），
 * 天然幂等。
 */
@Service
public class StateCardVersionStore {

    private final StateCardVersionJpaRepository repository;
    private final ObjectMapper objectMapper;

    public StateCardVersionStore(StateCardVersionJpaRepository repository,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 保存一版状态卡。card 序列化为 jsonb，estimated_tokens 单独成列。 */
    @Transactional
    public void save(StateCard card) {
        repository.save(new StateCardVersionEntity(
                card.taskId(),
                card.version(),
                objectMapper.writeValueAsString(card),
                card.estimatedTokens(),
                card.compiledAt()
        ));
    }

    /** 按版本取历史状态卡（回放用），反序列化回 domain record。 */
    @Transactional(readOnly = true)
    public Optional<StateCard> find(String taskId, long version) {
        return repository.findById(new StateCardVersionEntity.PK(taskId, version))
                .map(entity -> objectMapper.readValue(entity.getCard(), StateCard.class));
    }
}
