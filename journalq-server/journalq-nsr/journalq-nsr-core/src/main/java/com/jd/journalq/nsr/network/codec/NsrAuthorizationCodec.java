package com.jd.journalq.nsr.network.codec;

import com.jd.journalq.network.codec.AuthorizationCodec;
import com.jd.journalq.network.command.Authorization;
import com.jd.journalq.nsr.network.NsrPayloadCodec;
import com.jd.journalq.nsr.network.command.NsrCommandType;

/**
 * @author wylixiaobin
 * Date: 2019/3/20
 */
public class NsrAuthorizationCodec extends AuthorizationCodec implements NsrPayloadCodec<Authorization> {
    @Override
    public int type() {
        return NsrCommandType.AUTHORIZATION;
    }
}