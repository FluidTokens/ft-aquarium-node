package com.fluidtokens.aquarium.offchain.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/__internal__/healthcheck")
@Slf4j
@RequiredArgsConstructor
public class Healthcheck {


    @GetMapping
    public ResponseEntity<?> healthcheck() {

        return ResponseEntity.ok("ok");

    }

}
