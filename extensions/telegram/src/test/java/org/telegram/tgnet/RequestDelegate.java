/*
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package org.telegram.tgnet;

/** Test stand-in for Telegram's request callback, which takes a TLObject answer or a TL_error. */
public interface RequestDelegate {
    void run(Object response, TLRPC.TL_error error);
}
