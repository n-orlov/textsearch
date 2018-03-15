package com.example.textsearchbox.util.timelog;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StopWatch;

@Aspect
@Configuration
@Slf4j
@Profile("timelog")
public class TimeMeAspect {
    @Around("@annotation(com.example.textsearchbox.util.timelog.TimeMe)")
    public Object logTime(ProceedingJoinPoint joinPoint) throws Throwable {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            return joinPoint.proceed();
        } finally {
            stopWatch.stop();
            String classAndMethod = joinPoint.getTarget().getClass().getName() + "." + joinPoint.getSignature().getName();
            log.debug("{} execution time: {} ms", classAndMethod, stopWatch.getLastTaskTimeMillis());
        }
    }
}
