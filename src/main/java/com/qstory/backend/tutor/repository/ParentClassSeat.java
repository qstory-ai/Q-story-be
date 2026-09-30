package com.qstory.backend.tutor.repository;

import com.qstory.backend.org.entity.Organization;
import java.time.Instant;
import java.util.UUID;

/** 학부모의 아이 한 명이 들어가 있는 기관 반 - 이용권 판정에 필요한 값만 담는다. linkedAt은 좌석 순번의 기준이다. */
public record ParentClassSeat(UUID studentId, Instant linkedAt, Organization organization) {}
