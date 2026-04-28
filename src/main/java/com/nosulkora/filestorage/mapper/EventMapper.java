package com.nosulkora.filestorage.mapper;

import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.entity.User;

public class EventMapper {
    public static EventDto toDto(Event event) {
        if (event == null) {
            return null;
        }
        EventDto dto = new EventDto();
        dto.setId(event.getId());
        dto.setUserId(event.getUser() != null ? event.getUser().getId() : null);
        dto.setFileId(event.getFile() != null ? event.getFile().getId() : null);
        dto.setTimestamp(event.getTimeStamp());
        dto.setStatus(event.getStatus());
        return dto;
    }

    public static Event toEntity(EventDto dto, User user, File file) {
        if (dto == null) {
            return null;
        }
        Event event = new Event();
        event.setId(dto.getId());
        event.setFile(file);
        event.setUser(user);
        event.setTimeStamp(dto.getTimestamp());
        event.setStatus(dto.getStatus());
        return event;
    }
}
