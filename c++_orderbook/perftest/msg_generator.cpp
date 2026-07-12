//
// Created by rui zhou on 2020-05-16.
//

#include <cstdlib>
#include <string>
#include <iostream>
#include <fstream>
#include "feed_handler.h"
#include "error_monitor.h"
using namespace std;

// message format
// N,productid, orderid,side,quantity,price
namespace ob {
    class ObTester {
    private:
        FeedHandler feeder_;
    public:
        int productNums = 1;
        int quantityStart = 1;
        int quantityRange = 10;
        int priceRange = 5;
        int priceStart = 80;
        int orderSize = 10;
        string outputf = "messages.txt";
        bool printFlag = true;

    public:
        void run() {
            srand (time(NULL));
            vector<string> output;
            vector<string> tradeVec,orderVec;

            for (int i=1;i<=orderSize;i++) {
                auto productId = rand()%productNums + 1;
                string side = rand()%2==0?"B":"S";
                auto quantity = rand()%quantityRange + quantityStart;  // some 0 size order
                auto price =  rand()%priceRange + priceStart;

                // generate New order
                string msg="N,"+to_string(productId)+","+to_string(i)+","+side+","+to_string(quantity)+","+to_string(price);
                if (printFlag) {
                    cout<<msg<<endl;
                }
                output.emplace_back(msg);

                feeder_.processMessage(msg);

                do {
                    tradeVec.clear();
                    feeder_.orderBook_->doOrderMatch(tradeVec,orderVec);
                    // process the trade message
                    for (auto &iter:tradeVec){
                        if (printFlag) {
                            cout << iter << endl;
                        }
                        output.emplace_back(iter);
                        feeder_.processMessage(iter);
                    }
                } while (!tradeVec.empty());

                for (auto &iter:orderVec){
                    if (printFlag) {
                        cout << iter << endl;
                    }
                    output.emplace_back(iter);
                    feeder_.processMessage(iter);
                }
                orderVec.clear();
            }
            feeder_.printCurrentOrderBook(std::cout);
            ErrorMonitor::getInstance().printStats();

            cout << "=== RESULT ==========================================================\n";
            ofstream myfile(outputf);
            if (myfile.is_open())
            {
                for (auto &iter:output) {
                    myfile << iter << endl;
                }
                myfile.close();
                cout << "generated file " << outputf  <<endl;
            } else {
                cout << "Unable to open file";
            }

        }
    };
}
int main(int argc, char **argv) {
    ob::ObTester tester;
    if (argc==9) {
        tester.productNums = atoi(argv[1]);
        tester.quantityStart = atoi(argv[2]);
        tester.quantityRange = atoi(argv[3]);
        tester.priceRange = atoi(argv[4]);
        tester.priceStart = atoi(argv[5]);
        tester.orderSize = atoi(argv[6]);
        tester.outputf = argv[7];
        tester.printFlag = atoi(argv[8]);
    } else {
        cout << "use default setting" << endl;
    }
    tester.run();
    return 0;
}