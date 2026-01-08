package com.ruoyi.salesforce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.salesforce.domain.SfDataRunLog;
import com.ruoyi.salesforce.mapper.SfDataRunLogMapper;
import com.ruoyi.salesforce.service.ISfDataRunLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class SfDataRunLogServiceImpl implements ISfDataRunLogService {
    @Autowired
    private SfDataRunLogMapper logMapper;
    @Override
    public List<SfDataRunLog> selectLogList(Long jobId) {
        return logMapper.selectList(new LambdaQueryWrapper<SfDataRunLog>()
                .eq(SfDataRunLog::getJobId, jobId)
                .orderByDesc(SfDataRunLog::getStartTime));
    }

    @Override
    public String getResultFilePath(Long logId) {
        SfDataRunLog log = logMapper.selectById(logId);
        if(log == null) throw new ServiceException("日志不存在");
        return log.getResultFilePath();
    }
}
