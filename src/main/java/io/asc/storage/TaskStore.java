package io.asc.storage;

import io.asc.domain.Task;
import io.asc.storage.converter.TaskConverter;
import io.asc.storage.entity.TaskEntity;
import io.asc.storage.repository.TaskJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Task 的存储门面：application/core 层访问任务的唯一入口。
 *
 * <p>对外只暴露 domain.Task，调用方完全感知不到 JPA 和 TaskEntity 的存在；
 * 以后要换存储实现（比如 Redis 热状态）或者加缓存，改这一个类就够。
 */
@Repository
public class TaskStore {

    private final TaskJpaRepository jpaRepository;
    private final TaskConverter taskConverter;

    public TaskStore(TaskJpaRepository jpaRepository, TaskConverter taskConverter) {
        this.jpaRepository = jpaRepository;
        this.taskConverter = taskConverter;
    }

    /** 保存或更新任务，返回 domain 视图。 */
    @Transactional
    public Task save(Task task) {
        TaskEntity saved = jpaRepository.save(taskConverter.toEntity(task));
        return taskConverter.toDomain(saved);
    }

    /** 按 taskId 取任务。 */
    public Optional<Task> findById(String taskId) {
        return jpaRepository.findById(taskId).map(taskConverter::toDomain);
    }
}
