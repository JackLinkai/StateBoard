package io.asc.storage;

import io.asc.domain.StateItem;
import io.asc.storage.converter.StateItemConverter;
import io.asc.storage.entity.StateItemEntity;
import io.asc.storage.repository.StateItemJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * StateItem 的存储门面：application/core 层读写四类状态的唯一入口。
 *
 * <p>对外只暴露 domain.StateItem，domain → entity → jsonb 的序列化细节
 * 全部封在 StateItemConverter 里。调用方不需要知道“四类状态共表、
 * payload 存 jsonb”这些存储决策。
 */
@Repository
public class StateItemStore {

    private final StateItemJpaRepository jpaRepository;
    private final StateItemConverter stateItemConverter;

    public StateItemStore(StateItemJpaRepository jpaRepository, StateItemConverter stateItemConverter) {
        this.jpaRepository = jpaRepository;
        this.stateItemConverter = stateItemConverter;
    }

    /** 写入一条状态（Constraint / Fact / Todo / InvalidatedItem），返回 domain 视图。 */
    @Transactional
    public StateItem save(StateItem item) {
        StateItemEntity saved = jpaRepository.save(stateItemConverter.toEntity(item));
        return stateItemConverter.toDomain(saved);
    }

    /** 按状态 id 取单条。 */
    public Optional<StateItem> findById(String id) {
        return jpaRepository.findById(id).map(stateItemConverter::toDomain);
    }

    /** 取某任务的全部状态项，按 created_at、version 升序，恢复任务时按原始顺序重放。 */
    public List<StateItem> findByTaskId(String taskId) {
        return jpaRepository.findByTaskIdOrderByCreatedAtAscVersionAsc(taskId)
                .stream()
                .map(stateItemConverter::toDomain)
                .toList();
    }
}
