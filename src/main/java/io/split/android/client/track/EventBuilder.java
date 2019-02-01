package io.split.android.client.track;

import io.split.android.client.dtos.Event;
import io.split.android.client.validators.EventValidator;
import io.split.android.client.validators.EventValidatorImpl;

public class EventBuilder {

    public class EventValidationException extends Throwable {
        public EventValidationException(String message) {
            super(message);
        }
    }

    private String matchingKey;
    private String type;
    private String trafficType;
    private double value;

    public EventBuilder setMatchingKey(String key) {
        this.matchingKey = key;
        return this;
    }

    public EventBuilder setType(String type) {
        this.type = type;
        return this;
    }

    public EventBuilder setTrafficType(String trafficType) {
        this.trafficType = trafficType;
        return this;
    }

    public EventBuilder setValue(double value) {
        this.value = value;
        return this;
    }

    public Event build() throws EventValidationException {

        Event event = new Event();
        event.eventTypeId = type;
        event.trafficTypeName = trafficType;
        event.key = matchingKey;
        event.value = value;
        event.timestamp = System.currentTimeMillis();

        EventValidator eventValidator = new EventValidatorImpl("track");
        if (!eventValidator.isValidEvent(event)) {
            throw new EventValidationException("Event is not valid");
        }

        return event;
    }
}
