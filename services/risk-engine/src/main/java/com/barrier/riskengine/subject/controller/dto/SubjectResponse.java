package com.barrier.riskengine.subject.controller.dto;

/** Representação externa de um subject (cliente final) para o tenant dono do vínculo. */
public record SubjectResponse(String id, String documentType, String document, String name) {}
