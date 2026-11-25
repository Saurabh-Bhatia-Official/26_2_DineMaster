package com.dinemaster.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EdgeSyncPushRequest(@NotEmpty @Size(max = 100) List<@Valid EdgeSyncChangeRequest> changes) { }
