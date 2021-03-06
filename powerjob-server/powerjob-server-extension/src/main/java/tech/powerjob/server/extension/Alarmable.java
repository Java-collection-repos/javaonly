package tech.powerjob.server.extension;

import tech.powerjob.server.persistence.core.model.UserInfoDO;
import tech.powerjob.server.common.module.Alarm;

import java.util.List;

/**
 * 报警接口
 *
 * @author tjq
 * @since 2020/4/19
 */
public interface Alarmable {

    void onFailed(Alarm alarm, List<UserInfoDO> targetUserList);
}