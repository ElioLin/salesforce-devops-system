package com.ruoyi.salesforce.service;

import com.ruoyi.salesforce.domain.SfOrg;
import com.ruoyi.salesforce.service.impl.SfMetadataServiceImpl;

public interface ISfAuthService {
    <T> T executeWithRetry(Long orgId, SfMetadataServiceImpl.SfOperation<T> operation) throws Exception;

    void refreshAccessToken(SfOrg org);

    boolean isSessionExpired(Exception e);
}
