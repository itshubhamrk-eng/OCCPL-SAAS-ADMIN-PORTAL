/**
 * Cross-cutting building blocks (errors, ids, paging, time, web and security plumbing) used by
 * every module. Contains no business logic and depends on no other module.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.occpl.platform.shared;

import org.springframework.modulith.ApplicationModule;
