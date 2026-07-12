//
// Created by rui zhou on 2020-05-16.
//


#include <string>
#include <vector>
#include <sstream>
#include <iostream>
#include <iomanip>
#include "feed_handler.h"
#include "error_monitor.h"

using namespace ob;
using namespace std;


//------------------------------------------------------------
// orderbook unit test
namespace ob {

    class ObTester {
    private:
        FeedHandler feeder_;
        vector<string> summary_;
    public:
        //------------------------------------------------------------
        // main test entry point
        void runAllTests() {
            test1();
            test2();
            test3();
            test4();
            test5();
            test6();
            test7();
            printSummary();
        }


        //------------------------------------------------------------
        // record the test result
        void printResult(string testcase, bool result) {
            std::stringstream ss;
            ss << std::setw(-30)<< testcase <<" " <<(result?"PASSED":"FAILED") << endl;
            summary_.emplace_back(ss.str());
            cout << ss.str();
        }

        //------------------------------------------------------------
        // get the order book as string
        string getOrderBookStr(int pid) {
            auto &&buyOrderBook = feeder_.orderBook_->getBuyOrderBook(pid);
            auto &&sellOrderBook = feeder_.orderBook_->getSellOrderBook(pid);

            std::stringstream ss;

            for (auto it = sellOrderBook.rbegin(); it != sellOrderBook.rend(); ++it) {
                auto &&lst = it->second;
                ss<< it->first /100.;
                for (auto &&sz : lst) {
                    ss << "S" << sz.size;
                }
                ss << endl;
            }

            for (auto it = buyOrderBook.rbegin(); it != buyOrderBook.rend(); ++it) {
                auto &&lst = it->second;
                ss << it->first /100.;
                for (auto &&sz : lst) {
                    ss << "B" << sz.size;
                }
                ss << endl;
            }
            return ss.str();
        }

        //------------------------------------------------------------
        // print summary
        void printSummary() {
            cout << "=== SUMMARY ==========================================================\n";
            for (auto iter:summary_) {
                cout << iter;
            }
        }

        //------------------------------------------------------------
        // use match engine to generate message
        void test7() {
            feeder_.orderBook_->reset();
            ErrorMonitor::getInstance().reset();

            vector<string> inputs = {
                    "N,5,100000,S,1,1075",
                    "N,5,100001,B,9,1000",
                    "N,5,100002,B,30,975",
                    "N,5,100003,S,10,1050",
                    "N,5,100004,B,10,950",
                    "N,5,100005,S,2,1025",
                    "N,5,100006,B,1,1000",
                    "R,100004,B,10,950",
                    "N,5,100007,S,5,1025",
                    "N,5,100008,B,3,1050"
            };

            vector<string> output;

            for (auto &&iter:inputs) {
                output.emplace_back(iter);
                feeder_.processMessage(iter);

                vector<string> tradeVec,orderVec;
                do {
                    tradeVec.clear();
                    feeder_.orderBook_->doOrderMatch(tradeVec,orderVec);
                    // process the trade message
                    for (auto &iter:tradeVec){
                        cout << iter << endl;
                        output.emplace_back(iter);
                        feeder_.processMessage(iter);
                    }
                } while (!tradeVec.empty());

                for (auto &iter:orderVec){
                    cout << iter << endl;
                    output.emplace_back(iter);
                    feeder_.processMessage(iter);
                }
            }
            //printResult("test bid ask price cross(e.g.3)",ErrorMonitor::getInstance().bidAskCross_==1);
            cout <<"==***************\n";
            for (auto &iter:output) {
                cout << iter << endl;
            }

            feeder_.printCurrentOrderBook(std::cout);

            std::string actual = getOrderBookStr(5);
            std::string exp = "1075S1\n"
                              "1050S10\n"
                              "1025S4\n"
                              "1000B9B1\n"
                              "975B30\n";

            cout << actual<< endl;
            printResult("message generate test", exp==actual);
        }

        //------------------------------------------------------------
        // test bid/ask price cross e.g.3
        void test6() {
            feeder_.orderBook_->reset();
            ErrorMonitor::getInstance().reset();
            vector<string> inputs = {
                    "N,5,100001,S,10,1000",
                    "N,5,100008,B,9,1000",
                    "R,100008,B,9,1000   // missing trade message, report error too.",
                    "M,100001,S,1,1000   // "
            };
            for (auto &&iter:inputs) {
                feeder_.processMessage(iter);
            }
            printResult("test bid ask price cross(e.g.3)",ErrorMonitor::getInstance().bidAskCross_==1);
            feeder_.printCurrentOrderBook(std::cout);
        }


        //------------------------------------------------------------
        // test bid/ask price cross e.g.2
        void test5() {
            feeder_.orderBook_->reset();
            ErrorMonitor::getInstance().reset();
            vector<string> inputs = {
                    "N,5,100001,S,10,1000",
                    "N,5,100008,B,9,1000",
                    "N,5,100009,S,8,1900   // should be a trade message here, will report error",
                    "N,5,100010,B,1,1100   // still bid/ask price cross check error"
            };
            for (auto &&iter:inputs) {
                feeder_.processMessage(iter);
            }
            printResult("test bid ask price cross(e.g.2)",ErrorMonitor::getInstance().bidAskCross_==2);
            feeder_.printCurrentOrderBook(std::cout);
        }


        //------------------------------------------------------------
        // test bid/ask price cross e.g.1
        void test4() {
            feeder_.orderBook_->reset();
            ErrorMonitor::getInstance().reset();
            vector<string> inputs = {
                    "N,5,100001,S,10,1000",
                    "N,5,100008,B,9,1000",
                    "X,5,9,1000            // do order matching, so that orderbook can reflect the latest status",
                    "M,100001,S,1,1000     // bid/ask price cross check and no error found"
            };
            for (auto &&iter:inputs) {
                feeder_.processMessage(iter);
            }
            printResult("test bid ask price cross(e.g.1)",ErrorMonitor::getInstance().bidAskCross_==0);
        }




        //------------------------------------------------------------
        // test bid/ask price cross
        // test invalid msg data
        void test3() {
            feeder_.orderBook_->reset();
            ErrorMonitor::getInstance().reset();
            vector<string> inputs = {
                    "N,5,100001,S,10,1000",
                    "N,-5,100002,B,10,1000",  // invalid product id
                    "N,5,100007,B,0,1000",  // invalid quantity
                    "N,5,100008,B,9,1000",
                    "N,5,100009,S,8,1900"   // should be a trade message here
            };
            for (auto &&iter:inputs) {
                feeder_.processMessage(iter);
            }
            printResult("test bid ask price cross",ErrorMonitor::getInstance().bidAskCross_==1);
            printResult("test invalid msg data",ErrorMonitor::getInstance().invalidMsg_==1);
            printResult("test invalid productid",ErrorMonitor::getInstance().invalidProductId_==1);
        }


        //------------------------------------------------------------
        // this is to test if trade output correct
        void test2() {
            feeder_.orderBook_->reset();
            vector<string> inputs = {
                    "N,5,100000,S,10,1000",
                    "N,5,100001,S,8,900",
                    "N,5,100002,B,9,1000",
                    "X,5,8,900",
            };
            for (auto &&iter:inputs) {
                feeder_.processMessage(iter);
            }

            printResult("test most recent trade",
                        (feeder_.orderBook_->recentTradeProduct_ == 5 &&
                         feeder_.orderBook_->recentTradePrice_ == 90000 &&
                         feeder_.orderBook_->recentTradeSize_ == 8)
            );

            vector<string> inputs2 = {
                    "X,5,1,1000",
                    "R,100001,S,8,900",
                    "R,100002,B,9,1000",
                    "M,100000,S,9,1000"};

            for (auto &&iter:inputs2) {
                feeder_.processMessage(iter);
            }

            printResult("test most recent trade",
                        (feeder_.orderBook_->recentTradeProduct_ == 5 &&
                         feeder_.orderBook_->recentTradePrice_ == 100000 &&
                         feeder_.orderBook_->recentTradeSize_ == 1)
            );

            //feeder_.printCurrentOrderBook(std::cout);
        }

        //------------------------------------------------------------
        // this is to test the basic scenario described in the doc.
        void test1() {
            feeder_.orderBook_->reset();
            ErrorMonitor::getInstance().reset();
            vector<string> inputs = {
                    "N,5,100000,S,1,1075",
                    "N,5,100001,B,9,1000",
                    "N,5,100002,B,30,975",
                    "N,5,100003,S,10,1050",
                    "N,5,100004,B,10,950",
                    "N,5,100005,S,2,1025",
                    "N,5,100006,B,1,1000",
                    "R,100004,B,10,950",
                    "N,5,100007,S,5,1025",
                    "N,5,100008,B,3,1050",
                    "X,5,2,1025",
                    "X,5,1,1025",
                    "R,100008,B,3,1050",
                    "R,100005,S,2,1025",
                    "M,100007,S,4,1025"
            };
            for (auto &&iter:inputs) {
                feeder_.processMessage(iter);
            }
            feeder_.printCurrentOrderBook(std::cout);

            std::string actual = getOrderBookStr(5);
            std::string exp = "1075S1\n"
                              "1050S10\n"
                              "1025S4\n"
                              "1000B9B1\n"
                              "975B30\n";

            cout << actual<< endl;
            printResult("Basic unit test orderbook", exp==actual);
        }
    };
}


int main(int argc, char **argv) {
    ob::ObTester tester;
    tester.runAllTests();
    return 0;
}