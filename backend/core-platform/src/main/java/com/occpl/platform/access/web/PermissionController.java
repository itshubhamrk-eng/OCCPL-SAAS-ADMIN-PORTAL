package com.occpl.platform.access.web;

import com.occpl.platform.access.api.AccessCatalog;
import com.occpl.platform.access.api.PermissionScope;
import com.occpl.platform.access.api.PermissionView;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/permissions")
class PermissionController {

    private final AccessCatalog catalog;

    PermissionController(AccessCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "Permission catalogue", description = "Available to any authenticated user.")
    List<PermissionView> list(@RequestParam(defaultValue = "COMPANY") PermissionScope scope) {
        return catalog.permissions(scope);
    }
}
