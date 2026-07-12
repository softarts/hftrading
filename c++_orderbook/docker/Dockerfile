FROM ubuntu:bionic
MAINTAINER Zhou Rui <wirelesser@gmail.com>
ENV DEBIAN_FRONTEND noninteractive
WORKDIR /work

RUN apt-get -qq update && apt-get install -qy \
    build-essential autoconf git pkg-config \
    automake libtool curl make g++ unzip \
    gcc gdb git libboost1.65-all-dev cmake \
    libunwind-dev \
    vim \
    wget \
    maven \
    iputils-ping \
    dnsutils \
    software-properties-common \
    libbz2-dev libzstd-dev liblz4-dev libsnappy-dev


RUN apt clean && echo "CPP DEV with boost 1.65"

