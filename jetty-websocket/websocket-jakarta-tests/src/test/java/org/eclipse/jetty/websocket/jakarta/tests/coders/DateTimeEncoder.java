//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.tests.coders;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;

/**
 * Encode Date
 */
public class DateTimeEncoder implements Encoder.Text<Date>
{
    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    @Override
    public String encode(Date object) throws EncodeException
    {
        SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd G 'at' HH:mm:ss z");
        format.setTimeZone(GMT);
        return format.format(object);
    }

    @Override
    public void destroy()
    {
        CoderEventTracking.getInstance().addEvent(this, "destroy()");
    }

    @Override
    public void init(EndpointConfig config)
    {
        CoderEventTracking.getInstance().addEvent(this, "init(EndpointConfig)");
    }
}
