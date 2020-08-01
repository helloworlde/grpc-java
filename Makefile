build:
	gradle clean build -i -x test -x checkstyleJmh -x checkstyleMain -x checkstyleTest -x checkstyleTestShadow

install:
	gradle publishToMavenLocal