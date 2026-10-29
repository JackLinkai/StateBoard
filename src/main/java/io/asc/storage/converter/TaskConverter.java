package io.asc.storage.converter;

import io.asc.domain.Task;
import io.asc.storage.entity.TaskEntity;
import org.springframework.stereotype.Component;

/**
 * domain.Task（不可变 record）与 TaskEntity（JPA 实体）的双向转换。
 *
 * <p>status 枚举和数据库 TEXT 列之间用 name()/valueOf() 转换：
 * 显式转换发生在 converter 这一个点上，数据库里永远存的是枚举名，
 * 枚举加值不影响旧行，删值会在读取时快速失败而不是静默错位。
 */
@Component
public class TaskConverter {

    /** domain → entity。 */
    public TaskEntity toEntity(Task task) {
        return new TaskEntity(
                task.taskId(),
                task.description(),
                task.status().name(),
                task.createdAt(),
                task.updatedAt(),
                task.version()
        );
    }

    /** entity → domain。未知状态直接抛异常，避免脏数据静默通过。 */
    public Task toDomain(TaskEntity entity) {
        return new Task(
                entity.getTaskId(),
                entity.getDescription(),
                Task.TaskStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
