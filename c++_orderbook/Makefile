all: orderbook
	@echo DONE

orderbook: build-cppdev
	@rm -rf build && mkdir -p build
	@sudo docker run -it --rm \
	    -v `pwd`/:/work \
	    cppdev:latest \
	    sh -c "cd /work/build && cmake ../ && make && make install"


build-cppdev:
	@docker build -t cppdev ./docker
