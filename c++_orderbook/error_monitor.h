//
// Created by rui zhou on 2020-05-15.
//

#ifndef ORDERBOOK_ERROR_MONITOR_H
#define ORDERBOOK_ERROR_MONITOR_H

#include <stdint.h>
#include <iostream>
#include <iomanip>


namespace ob{
    class ErrorMonitor {
        friend class ObTester;
        uint64_t    duplicateAdd_ = 0;
        uint64_t    corruptedMsg_ = 0;
        uint64_t    invalidMsg_ = 0;
        uint64_t    bidAskCross_ = 0;
        uint64_t    tradeOnMissingOrder_ = 0;
        uint64_t    invalidTradeSize_ = 0;
        uint64_t    modifyRemoveMissingOrder_ = 0;
        uint64_t    modifyIgnored_ = 0;
        uint64_t    removeWrongData_ = 0;
        uint64_t    modifyOrderDeleted_ = 0;
        uint64_t    invalidProductId_ = 0;
    public:
        static ErrorMonitor& getInstance() {
            static ErrorMonitor instance;
            return instance;
        }

        void reset() {
            duplicateAdd_ = 0;
            corruptedMsg_ = 0;
            invalidMsg_ = 0;
            bidAskCross_ = 0;
            tradeOnMissingOrder_ = 0;
            invalidTradeSize_ = 0;
            modifyRemoveMissingOrder_ = 0;
            modifyIgnored_ = 0;
            removeWrongData_ = 0;
            modifyOrderDeleted_ = 0;
            invalidProductId_ = 0;
        }

        void corruptMessage() {
            corruptedMsg_++;
        }

        // negative, invalid trade size
        void invalidMsg() {
            invalidMsg_++;
        }

        void invalidProductId() {
            invalidProductId_++;
        }

        void duplicateAdd() {
            duplicateAdd_++;
        }

        void crossBidAsk() {
            bidAskCross_++;
        }

        void tradeOnMissingOrder() {
            tradeOnMissingOrder_++;
        }

        void modifyRemoveMissingOrder() {
            modifyRemoveMissingOrder_++;
        }

        void removeWithWrongData() {
            removeWrongData_++;
        }

        void invalidTradeSize() {
            invalidTradeSize_++;
        }

        void modifyIgnored() {
            modifyIgnored_++;
        }

        void modifyOnDeletedOrder() {
            modifyOrderDeleted_++;
        }

        void printStats() {
            std::cout << "--- STATS --------------------------------------\n";
            int align = 10;
            int c0 = 50;
            std::cout << std::setw(c0)<< "Corrupted Message " << std::setw(align) <<corruptedMsg_ <<std::endl;
            std::cout << std::setw(c0)<< "Duplicated Add " << std::setw(align) << duplicateAdd_ <<std::endl;
            std::cout << std::setw(c0)<< "Trade on missing order " << std::setw(align) << tradeOnMissingOrder_  <<std::endl;
            std::cout << std::setw(c0)<< "Remove/Modify with no corresponding order " << std::setw(align) << modifyRemoveMissingOrder_ <<std::endl;
            std::cout << std::setw(c0)<< "No trade occur when ask/price cross " << std::setw(align) << bidAskCross_ <<std::endl;
            std::cout << std::setw(c0)<< "Invalid Message(negative/invalid data) "  << std::setw(align) << invalidMsg_ <<std::endl;
            std::cout << std::setw(c0)<< "Invalid product ID " << std::setw(align) << invalidProductId_ <<std::endl;
            std::cout << std::setw(c0)<< "Remove order with wrong data " << std::setw(align) << removeWrongData_ <<std::endl;
            std::cout << std::setw(c0)<< "Modify on a deleted order " << std::setw(align) << modifyOrderDeleted_ <<std::endl;

            //std::cout << std::setw(40) << "Invalid trade size " << invalidTradeSize_ <<std::endl;
            //std::cout << std::setw(align) << "Modify ignored " <<  modifyIgnored_ <<std::endl;

        }
    };
}
#endif //ORDERBOOK_ERROR_MONITOR_H
