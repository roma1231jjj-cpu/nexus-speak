package com.local.aivoice;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

public class AssistantSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new AssistantSession(this);
    }

    public static class AssistantSession extends VoiceInteractionSession {
        public AssistantSession(Service service) {
            super(service);
        }

        @Override
        public void onShow(Bundle args, int showFlags) {
            getContext().startActivity(AssistantActivity.internalIntent(getContext()));
        }
    }
}
