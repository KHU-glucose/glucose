package com.glucoselog.glucose;

/** ml-service 계약(docs/ml-service-contract.md)의 readings[].flag와 같은 값. */
public enum ReadingFlag {
    NORMAL, ABOVE_RANGE, BELOW_RANGE, MISSING
}
