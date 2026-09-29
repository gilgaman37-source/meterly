package com.example.metermonitor.common;

public final class DomainTypes {
    private DomainTypes() {}

    public enum Role { USER, ANALYST, ADMIN }
    public enum MeterType { COLD_WATER, HOT_WATER, GAS }
    public enum ReadingSource { MANUAL, IMAGE_OCR }
    public enum ProcessingStatus { QUEUED, PROCESSING, COMPLETED, FAILED, REQUIRES_CONFIRMATION }
    public enum TariffBillingMethod { SINGLE_RATE, TWO_COMPONENT_HOT_WATER }
}
